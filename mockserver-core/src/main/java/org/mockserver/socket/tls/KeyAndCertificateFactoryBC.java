package org.mockserver.socket.tls;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.bc.BcX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.IPAddress;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.xml.bind.DatatypeConverter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.mockserver.configuration.ConfigurationProperties.directoryToSaveDynamicSSLCertificate;
import static org.slf4j.event.Level.DEBUG;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom, ganskef
 */
public class KeyAndCertificateFactoryBC implements KeyAndCertificateFactory {

    private final MockServerLogger mockServerLogger;

    private static final String PROVIDER_NAME = BouncyCastleProvider.PROVIDER_NAME;
    private static final String SIGNATURE_ALGORITHM = "SHA256WithRSAEncryption";
    private static final String KEY_GENERATION_ALGORITHM = "RSA";
    /**
     * Generates an 2048 bit RSA key pair using SHA1PRNG for the Certificate Authority.
     */
    private static final int ROOT_KEYSIZE = 2048;
    /**
     * Generates an 2048 bit RSA key pair using SHA1PRNG for the server
     * certificates.
     */
    private static final int FAKE_KEYSIZE = 2048;
    /**
     * Current time minus 1 year, just in case software clock goes back due to
     * time synchronization
     */
    private static final Date NOT_BEFORE = new Date(System.currentTimeMillis() - 86400000L * 365);
    /**
     * The maximum possible value in X.509 specification: 9999-12-31 23:59:59,
     * new Date(253402300799000L), but Apple iOS 8 fails with a certificate
     * expiration date grater than Mon, 24 Jan 6084 02:07:59 GMT (issue #6).
     * <p>
     * Hundred years in the future from starting the proxy should be enough.
     */
    private static final Date NOT_AFTER = new Date(System.currentTimeMillis() + 86400000L * 365 * 100);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private String mockServerCertificatePEMFile;
    private String mockServerPrivateKeyPEMFile;

    KeyAndCertificateFactoryBC(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public void addSubjectAlternativeName(String host) {
        if (host != null) {
            String hostWithoutPort = substringBefore(host, ":");
            if (IPAddress.isValid(hostWithoutPort)) {
                ConfigurationProperties.addSslSubjectAlternativeNameIps(hostWithoutPort);
            } else {
                ConfigurationProperties.addSslSubjectAlternativeNameDomains(hostWithoutPort);
            }
        }
    }

    private SubjectKeyIdentifier createSubjectKeyIdentifier(Key key) throws IOException {
        try (ASN1InputStream is = new ASN1InputStream(new ByteArrayInputStream(key.getEncoded()))) {
            ASN1Sequence seq = (ASN1Sequence) is.readObject();
            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(seq);
            return new BcX509ExtensionUtils().createSubjectKeyIdentifier(info);
        }
    }

    private X509Certificate signCertificate(X509v3CertificateBuilder certificateBuilder, PrivateKey signedWithPrivateKey) throws OperatorCreationException, CertificateException {
        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(PROVIDER_NAME).build(signedWithPrivateKey);
        return new JcaX509CertificateConverter().setProvider(PROVIDER_NAME).getCertificate(certificateBuilder.build(signer));
    }

    public static void main(String[] args) throws Exception {
        new KeyAndCertificateFactoryBC(new MockServerLogger()).buildAndSaveCertificateAuthorityCertificates();
    }

    /**
     * Create a random 2048 bit RSA key pair with the given length
     */
    private KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_GENERATION_ALGORITHM, PROVIDER_NAME);
        generator.initialize(keySize, new SecureRandom());
        return generator.generateKeyPair();
    }

    /**
     * Create a certificate to use by a Certificate Authority, signed by a self signed certificate.
     */
    private X509Certificate createCACert(PublicKey publicKey, PrivateKey privateKey) throws Exception {

        // signers name
        X500Name issuerName = new X500Name("CN=www.mockserver.com, O=MockServer, L=London, ST=England, C=UK");

        // serial
        BigInteger serial = BigInteger.valueOf(new Random().nextInt(Integer.MAX_VALUE));

        // create the certificate - version 3 (with subjects name same as issues as self signed)
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName, serial, NOT_BEFORE, NOT_AFTER, issuerName, publicKey);
        builder.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        KeyUsage usage = new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment | KeyUsage.cRLSign);
        builder.addExtension(Extension.keyUsage, false, usage);

        ASN1EncodableVector purposes = new ASN1EncodableVector();
        purposes.add(KeyPurposeId.id_kp_serverAuth);
        purposes.add(KeyPurposeId.id_kp_clientAuth);
        purposes.add(KeyPurposeId.anyExtendedKeyUsage);
        builder.addExtension(Extension.extendedKeyUsage, false, new DERSequence(purposes));

        X509Certificate cert = signCertificate(builder, privateKey);
        cert.checkValidity(new Date());
        cert.verify(publicKey);

        return cert;
    }

    /**
     * Create a server certificate for the given domain and subject alternative names, signed by the given Certificate Authority.
     */
    private X509Certificate createCASignedCert(PublicKey publicKey, X509Certificate certificateAuthorityCert, PrivateKey certificateAuthorityPrivateKey, PublicKey certificateAuthorityPublicKey, String domain, String[] subjectAlternativeNameDomains, String[] subjectAlternativeNameIps) throws Exception {

        // signers name
        X500Name issuer = new X509CertificateHolder(certificateAuthorityCert.getEncoded()).getSubject();

        // subjects name - the same as we are self signed.
        X500Name subject = new X500Name("CN=" + domain + ", O=MockServer, L=London, ST=England, C=UK");

        // serial
        BigInteger serial = BigInteger.valueOf(new Random().nextInt(Integer.MAX_VALUE));

        // create the certificate - version 3
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serial, NOT_BEFORE, NOT_AFTER, subject, publicKey);
        builder.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

        // subject alternative name
        List<ASN1Encodable> subjectAlternativeNames = new ArrayList<>();
        if (subjectAlternativeNameDomains != null) {
            subjectAlternativeNames.add(new GeneralName(GeneralName.dNSName, domain));
            for (String subjectAlternativeNameDomain : subjectAlternativeNameDomains) {
                subjectAlternativeNames.add(new GeneralName(GeneralName.dNSName, subjectAlternativeNameDomain));
            }
        }
        if (subjectAlternativeNameIps != null) {
            for (String subjectAlternativeNameIp : subjectAlternativeNameIps) {
                if (IPAddress.isValidIPv6WithNetmask(subjectAlternativeNameIp)
                    || IPAddress.isValidIPv6(subjectAlternativeNameIp)
                    || IPAddress.isValidIPv4WithNetmask(subjectAlternativeNameIp)
                    || IPAddress.isValidIPv4(subjectAlternativeNameIp)) {
                    subjectAlternativeNames.add(new GeneralName(GeneralName.iPAddress, subjectAlternativeNameIp));
                }
            }
        }
        if (subjectAlternativeNames.size() > 0) {
            DERSequence subjectAlternativeNamesExtension = new DERSequence(subjectAlternativeNames.toArray(new ASN1Encodable[0]));
            builder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNamesExtension);
        }

        X509Certificate cert = signCertificate(builder, certificateAuthorityPrivateKey);

        cert.checkValidity(new Date());
        cert.verify(certificateAuthorityPublicKey);

        return cert;
    }

    /**
     * Regenerate Certificate Authority public/private keys and X.509 certificate
     * <p>
     * Note: X.509 certificate should be stable so this method should rarely be used
     */
    private synchronized void buildAndSaveCertificateAuthorityCertificates() throws Exception {
        KeyPair caKeyPair = generateKeyPair(ROOT_KEYSIZE);

        saveCertificateAsPEMFile(createCACert(caKeyPair.getPublic(), caKeyPair.getPrivate()), "CertificateAuthorityCertificate.pem", false, "X509 key");
        saveCertificateAsPEMFile(caKeyPair.getPublic(), "CertificateAuthorityPublicKey.pem", false, "public key");
        saveCertificateAsPEMFile(caKeyPair.getPrivate(), "CertificateAuthorityPrivateKey.pem", false, "private key");
    }

    /**
     * Create a KeyStore with a server certificate for the given domain and subject alternative names.
     */
    public synchronized void buildAndSaveCertificates() {
        try {
            // personal keys
            KeyPair keyPair = generateKeyPair(FAKE_KEYSIZE);
            PrivateKey mockServerPrivateKey = keyPair.getPrivate();
            PublicKey mockServerPublicKey = keyPair.getPublic();

            // ca keys
            PrivateKey caPrivateKey = mockServerCertificateAuthorityPrivateKey();
            X509Certificate caCert = certificateAuthorityX509Certificate();

            // generate mockServer certificate
            X509Certificate mockServerCert = createCASignedCert(
                mockServerPublicKey,
                caCert,
                caPrivateKey,
                caCert.getPublicKey(),
                ConfigurationProperties.sslCertificateDomainName(),
                ConfigurationProperties.sslSubjectAlternativeNameDomains(),
                ConfigurationProperties.sslSubjectAlternativeNameIps()
            );
            String randomUUID = UUID.randomUUID().toString();
            mockServerCertificatePEMFile = saveCertificateAsPEMFile(mockServerCert, "MockServerCertificate" + randomUUID + ".pem", true, "X509 key");
            mockServerPrivateKeyPEMFile = saveCertificateAsPEMFile(mockServerPrivateKey, "MockServerPrivateKey" + randomUUID + ".pem", true, "private key");
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.EXCEPTION)
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while refreshing certificates")
                    .setThrowable(e)
            );
        }
    }

    /**
     * Saves X509Certificate as Base-64 encoded PEM file.
     */
    private String saveCertificateAsPEMFile(Object x509Certificate, String filename, boolean deleteOnExit, String type) throws IOException {
        File pemFile;
        if (isNotBlank(directoryToSaveDynamicSSLCertificate()) && new File(directoryToSaveDynamicSSLCertificate()).exists()) {
            pemFile = new File(new File(directoryToSaveDynamicSSLCertificate()), filename);
            if (pemFile.exists()) {
                boolean deletedFile = pemFile.delete();
                if (!deletedFile) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(LogEntry.LogMessageType.WARN)
                            .setLogLevel(WARN)
                            .setMessageFormat("failed to delete dynamic TLS certificate " + type + " PEM file at " + pemFile.getAbsolutePath() + " prior to creating new version")
                    );
                }
            }
            boolean createFile = pemFile.createNewFile();
            if (!createFile) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.WARN)
                        .setLogLevel(WARN)
                        .setMessageFormat("failed to created dynamic TLS certificate " + type + " PEM file at{}")
                        .setArguments(pemFile.getAbsolutePath())
                );
            }
        } else {
            pemFile = File.createTempFile(filename, null);
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(LogEntry.LogMessageType.DEBUG)
                .setLogLevel(DEBUG)
                .setMessageFormat("created dynamic TLS certificate " + type + " PEM file at{}")
                .setArguments(pemFile.getAbsolutePath())
        );
        try (FileWriter pemfileWriter = new FileWriter(pemFile)) {
            try (JcaPEMWriter jcaPEMWriter = new JcaPEMWriter(pemfileWriter)) {
                jcaPEMWriter.writeObject(x509Certificate);
            }
        }
        if (deleteOnExit) {
            pemFile.deleteOnExit();
        }
        return pemFile.getAbsolutePath();
    }

    public PrivateKey privateKey() {
        return loadPrivateKeyFromPEMFile(mockServerPrivateKeyPEMFile);
    }

    public X509Certificate x509Certificate() {
        return loadX509FromPEMFile(mockServerCertificatePEMFile);
    }

    public boolean certificateCreated() {
        return validX509PEMFileExists(mockServerCertificatePEMFile);
    }

    public X509Certificate certificateAuthorityX509Certificate() {
        return loadX509FromPEMFile(ConfigurationProperties.certificateAuthorityCertificate());
    }

    private RSAPrivateKey mockServerCertificateAuthorityPrivateKey() {
        return loadPrivateKeyFromPEMFile(ConfigurationProperties.certificateAuthorityPrivateKey());
    }

    /**
     * Load PrivateKey from PEM file.
     */
    private RSAPrivateKey loadPrivateKeyFromPEMFile(String filename) {
        try {
            String publicKeyFile = FileReader.readFileFromClassPathOrPath(filename);
            byte[] publicKeyBytes = DatatypeConverter.parseBase64Binary(publicKeyFile.replace("-----BEGIN RSA PRIVATE KEY-----", "").replace("-----END RSA PRIVATE KEY-----", ""));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(publicKeyBytes));
        } catch (Exception e) {
            throw new RuntimeException("Exception reading private key from PEM file", e);
        }
    }

    /**
     * Load X509 from PEM file.
     */
    private X509Certificate loadX509FromPEMFile(String filename) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(FileReader.openStreamToFileFromClassPathOrPath(filename));
        } catch (Exception e) {
            throw new RuntimeException("Exception reading X509 from PEM file", e);
        }
    }

    /**
     * Check if PEM file exists.
     */
    private boolean validX509PEMFileExists(String filename) {
        try {
            return CertificateFactory.getInstance("X.509").generateCertificate(FileReader.openStreamToFileFromClassPathOrPath(filename)) != null;
        } catch (Exception e) {
            return false;
        }
    }

}
