/*
 * Copyright 2015 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sk.koomp.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;

import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertID;
import org.bouncycastle.asn1.ess.SigningCertificate;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

public abstract class CreateSignatureBase implements SignatureInterface {
	private PrivateKey privateKey;
	private Certificate[] certificateChain;
	private String tsaUrl;
	private boolean externalSigning;

	/**
	 * Initialize the signature creator with a keystore (pkcs12) and pin that should
	 * be used for the signature.
	 *
	 * @param keystore is a pkcs12 keystore.
	 * @param pin      is the pin for the keystore / private key
	 * @throws KeyStoreException         if the keystore has not been initialized
	 *                                   (loaded)
	 * @throws NoSuchAlgorithmException  if the algorithm for recovering the key
	 *                                   cannot be found
	 * @throws UnrecoverableKeyException if the given password is wrong
	 * @throws CertificateException      if the certificate is not valid as signing
	 *                                   time
	 * @throws IOException               if no certificate could be found
	 */
	public CreateSignatureBase(KeyStore keystore, char[] pin) throws KeyStoreException, UnrecoverableKeyException,
			NoSuchAlgorithmException, IOException, CertificateException {
		// grabs the first alias from the keystore and get the private key. An
		// alternative method or constructor could be used for setting a specific
		// alias that should be used.
		Enumeration<String> aliases = keystore.aliases();
		String alias;
		Certificate cert = null;
		while (cert == null && aliases.hasMoreElements()) {
			alias = aliases.nextElement();
			setPrivateKey((PrivateKey) keystore.getKey(alias, pin));
			Certificate[] certChain = keystore.getCertificateChain(alias);
			if (certChain != null) {
				setCertificateChain(certChain);
				cert = certChain[0];
				if (cert instanceof X509Certificate) {
					// avoid expired certificate
					((X509Certificate) cert).checkValidity();

					SigUtils.checkCertificateUsage((X509Certificate) cert);
				}
			}
		}

		if (cert == null) {
			throw new IOException("Could not find certificate");
		}
	}

	public final void setPrivateKey(PrivateKey privateKey) {
		this.privateKey = privateKey;
	}

	public final void setCertificateChain(final Certificate[] certificateChain) {
		this.certificateChain = certificateChain;
	}

	public Certificate[] getCertificateChain() {
		return certificateChain;
	}

	public void setTsaUrl(String tsaUrl) {
		this.tsaUrl = tsaUrl;
	}

	/**
	 * SignatureInterface sample implementation.
	 * <p>
	 * This method will be called from inside of the pdfbox and create the PKCS #7
	 * signature. The given InputStream contains the bytes that are given by the
	 * byte range.
	 * <p>
	 * This method is for internal use only.
	 * <p>
	 * Use your favorite cryptographic library to implement PKCS #7 signature
	 * creation. If you want to create the hash and the signature separately (e.g.
	 * to transfer only the hash to an external application), read
	 * <a href="https://stackoverflow.com/questions/41767351">this answer</a> or
	 * <a href="https://stackoverflow.com/questions/56867465">this answer</a>.
	 * <p>
	 * Added Signing certificate attribute to satisfy PAdES requirements (ETSI EN
	 * 319 142-1 V1.1.1)
	 * </p>
	 * 
	 * @throws IOException
	 */
	@Override
	public byte[] sign(InputStream content) throws IOException {
		// cannot be done private (interface)
		try {
			CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
			X509Certificate cert = (X509Certificate) certificateChain[0];

			// hash alg
			MessageDigest md = MessageDigest.getInstance("SHA1");

			byte[] derCert = cert.getEncoded();
			md.update(derCert);
			byte[] certDigest = md.digest();

			// Create IssuerSerial object
			final X500Name issuerX500Name = new X509CertificateHolder(cert.getEncoded()).getIssuer();
			final GeneralName generalName = new GeneralName(issuerX500Name);
			final GeneralNames generalNames = new GeneralNames(generalName);
			final BigInteger serialNumber = cert.getSerialNumber();
			IssuerSerial theIssuerSerial = new IssuerSerial(generalNames, serialNumber);

			// v2 MessageDigest alg needs to be SHA-256 then
			// sha256 with rsa encryption oid 1.2.840.113549.1.1.11
//			ESSCertIDv2 essCert = new ESSCertIDv2(
//					new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.840.113549.1.1.11"), DERNull.INSTANCE),
//					certDigest, theIssuerSerial);
//
//			SigningCertificateV2 signingCertificateV2 = new SigningCertificateV2(essCert);
//			Attribute attribute = new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
//					new DERSet(signingCertificateV2));

			final ESSCertID essCertID = new ESSCertID(certDigest, theIssuerSerial);
			SigningCertificate signingCertificate = new SigningCertificate(essCertID);
			Attribute attribute = new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificate,
					new DERSet(signingCertificate));

			// SignedAttributeTableGenerator
			ASN1EncodableVector signedAttributes = new ASN1EncodableVector();
			signedAttributes.add(attribute);
			AttributeTable attributeTable = new AttributeTable(signedAttributes);
			DefaultSignedAttributeTableGenerator attributeTableGenerator = new DefaultSignedAttributeTableGenerator(
					attributeTable);

			ContentSigner sha1Signer = new JcaContentSignerBuilder("SHA256WithRSA").build(privateKey);
			Provider p = new BouncyCastleProvider();
			DigestCalculatorProvider digestProvider = new JcaDigestCalculatorProviderBuilder().setProvider(p).build();

			SignerInfoGenerator signerInfoGenerator = new JcaSignerInfoGeneratorBuilder(digestProvider)
					.setSignedAttributeGenerator(attributeTableGenerator).build(sha1Signer, cert);

			gen.addSignerInfoGenerator(signerInfoGenerator);
			gen.addCertificates(new JcaCertStore(Arrays.asList(certificateChain)));
			CMSProcessableInputStream msg = new CMSProcessableInputStream(content);
			CMSSignedData signedData = gen.generate(msg, false);
			if (tsaUrl != null && tsaUrl.length() > 0) {
				ValidationTimeStamp validation = new ValidationTimeStamp(tsaUrl);
				signedData = validation.addSignedTimeStamp(signedData);
			}
			return signedData.getEncoded();
		} catch (GeneralSecurityException | CMSException | OperatorCreationException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Set if external signing scenario should be used. If {@code false},
	 * SignatureInterface would be used for signing.
	 * <p>
	 * Default: {@code false}
	 * </p>
	 * 
	 * @param externalSigning {@code true} if external signing should be performed
	 */
	public void setExternalSigning(boolean externalSigning) {
		this.externalSigning = externalSigning;
	}

	public boolean isExternalSigning() {
		return externalSigning;
	}
}
