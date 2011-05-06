/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.swssf.impl.saml;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opensaml.saml1.core.ConfirmationMethod;
import org.opensaml.saml1.core.Subject;
import org.opensaml.saml1.core.SubjectConfirmation;
import org.opensaml.saml1.core.SubjectStatement;
import org.opensaml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.SAMLSignatureProfileValidator;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.security.x509.BasicX509Credential;
import org.opensaml.xml.signature.KeyInfo;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.SignatureValidator;
import org.opensaml.xml.validation.ValidationException;
import org.swssf.crypto.Crypto;
import org.swssf.crypto.CryptoType;
import org.swssf.ext.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.KeyValue;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.keyinfo.X509IssuerSerial;
import javax.xml.namespace.QName;
import java.math.BigInteger;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * @author $Author: $
 * @version $Revision: $ $Date: $
 */
public class SAMLAssertionWrapper {

    protected static final transient Log logger = LogFactory.getLog(SAMLAssertionWrapper.class);

    /**
     * Raw SAML assertion data
     */
    private XMLObject xmlObject = null;
    /**
     * Typed SAML v1.1 assertion
     */
    private org.opensaml.saml1.core.Assertion saml1 = null;

    /**
     * Typed SAML v2.0 assertion
     */
    private org.opensaml.saml2.core.Assertion saml2 = null;

    public SAMLAssertionWrapper(Element element) throws WSSecurityException {
        OpenSAMLUtil.initSamlEngine();
        this.xmlObject = OpenSAMLUtil.fromDom(element);
        if (xmlObject instanceof org.opensaml.saml2.core.Assertion) {
            this.saml2 = (org.opensaml.saml2.core.Assertion) xmlObject;
        } else if (xmlObject instanceof org.opensaml.saml1.core.Assertion) {
            this.saml1 = (org.opensaml.saml1.core.Assertion) xmlObject;
        }
    }

    /**
     * Method getId returns the id of this AssertionWrapper object.
     *
     * @return the id (type String) of this AssertionWrapper object.
     */
    public String getId() {
        String id = null;
        if (saml2 != null) {
            id = saml2.getID();
        } else {
            id = saml1.getID();
        }
        return id;
    }

    public boolean isSigned() {
        if (saml2 != null) {
            return saml2.isSigned() || saml2.getSignature() != null;
        } else {
            return saml1.isSigned() || saml1.getSignature() != null;
        }
    }

    /**
     * Method getIssuerString returns the issuerString of this AssertionWrapper object.
     *
     * @return the issuerString (type String) of this AssertionWrapper object.
     */
    public String getIssuerString() {
        if (saml2 != null && saml2.getIssuer() != null) {
            return saml2.getIssuer().getValue();
        } else if (saml1 != null) {
            return saml1.getIssuer();
        }
        logger.error(
                "AssertionWrapper: unable to return Issuer string - no saml assertion "
                        + "object or issuer is null"
        );
        return null;
    }

    /**
     * Verify the signature of this assertion
     *
     * @throws ValidationException
     */
    public SAMLKeyInfo verifySignature(SecurityProperties securityProperties) throws WSSecurityException {
        Signature sig = null;
        if (saml2 != null && saml2.getSignature() != null) {
            sig = saml2.getSignature();
        } else if (saml1 != null && saml1.getSignature() != null) {
            sig = saml1.getSignature();
        }

        KeyInfo keyInfo = sig.getKeyInfo();
        SAMLKeyInfo samlKeyInfo = getCredentialFromKeyInfo(keyInfo.getDOM(), securityProperties);

        if (samlKeyInfo == null) {
            throw new WSSecurityException(
                    WSSecurityException.FAILURE, "invalidSAMLsecurity",
                    new Object[]{"cannot get certificate or key"}
            );
        }

        SAMLSignatureProfileValidator validator = new SAMLSignatureProfileValidator();
        try {
            validator.validate(sig);
        } catch (ValidationException ex) {
            throw new WSSecurityException("SAML signature validation failed", ex);
        }

        BasicX509Credential credential = new BasicX509Credential();
        if (samlKeyInfo.getCerts() != null) {
            credential.setEntityCertificate(samlKeyInfo.getCerts()[0]);
        } else if (samlKeyInfo.getPublicKey() != null) {
            credential.setPublicKey(samlKeyInfo.getPublicKey());
        } else {
            throw new WSSecurityException(
                    WSSecurityException.FAILURE, "invalidSAMLsecurity",
                    new Object[]{"cannot get certificate or key"}
            );
        }
        SignatureValidator sigValidator = new SignatureValidator(credential);
        try {
            sigValidator.validate(sig);
        } catch (ValidationException ex) {
            throw new WSSecurityException("SAML signature validation failed", ex);
        }
        return samlKeyInfo;
    }

    /**
     * This method returns a SAMLKeyInfo corresponding to the credential found in the
     * KeyInfo (DOM Element) argument.
     *
     * @param keyInfoElement The KeyInfo as a DOM Element
     * @param data           The RequestData instance used to obtain configuration
     * @param docInfo        A WSDocInfo instance
     * @param bspCompliant   Whether to process tokens in compliance with the BSP spec or not
     * @return The credential (as a SAMLKeyInfo object)
     * @throws WSSecurityException
     */
    private SAMLKeyInfo getCredentialFromKeyInfo(Element keyInfoElement, SecurityProperties securityProperties) throws WSSecurityException {
        // First try to find an EncryptedKey or a BinarySecret via DOM
        Node node = keyInfoElement.getFirstChild();
        while (node != null) {
            if (Node.ELEMENT_NODE == node.getNodeType()) {
                QName el = new QName(node.getNamespaceURI(), node.getLocalName());
                if (el.equals(Constants.TAG_xenc_EncryptedKey)) {
                    //todo:
                    /*
                    EncryptedKeyProcessor proc = new EncryptedKeyProcessor();
                    List<WSSecurityEngineResult> result =
                            proc.handleToken((Element) node, data, docInfo);
                    byte[] secret =
                            (byte[]) result.get(0).get(
                                    WSSecurityEngineResult.TAG_SECRET
                            );
                    return new SAMLKeyInfo(secret);
                    */
                    return null;
                } else if (el.equals(Constants.TAG_wst_BinarySecret)) {
                    Text txt = (Text) node.getFirstChild();
                    return new SAMLKeyInfo(Base64.decodeBase64(txt.getData()));
                }
            }
            node = node.getNextSibling();
        }

        // Next marshal the KeyInfo DOM element into a javax KeyInfo object and get the
        // (public key) credential
        X509Certificate[] certs = null;
        KeyInfoFactory keyInfoFactory = KeyInfoFactory.getInstance("DOM");
        XMLStructure keyInfoStructure = new DOMStructure(keyInfoElement);

        try {
            javax.xml.crypto.dsig.keyinfo.KeyInfo keyInfo =
                    keyInfoFactory.unmarshalKeyInfo(keyInfoStructure);
            List<?> list = keyInfo.getContent();

            for (int i = 0; i < list.size(); i++) {
                XMLStructure xmlStructure = (XMLStructure) list.get(i);
                if (xmlStructure instanceof KeyValue) {
                    PublicKey publicKey = ((KeyValue) xmlStructure).getPublicKey();
                    return new SAMLKeyInfo(publicKey);
                } else if (xmlStructure instanceof X509Data) {
                    List<?> x509Data = ((X509Data) xmlStructure).getContent();
                    for (int j = 0; j < x509Data.size(); j++) {
                        Object x509obj = x509Data.get(j);
                        if (x509obj instanceof X509Certificate) {
                            certs = new X509Certificate[1];
                            certs[0] = (X509Certificate) x509obj;
                            return new SAMLKeyInfo(certs);
                        } else if (x509obj instanceof X509IssuerSerial) {
                            if (securityProperties.getSignatureVerificationCrypto() == null) {
                                throw new WSSecurityException(
                                        WSSecurityException.FAILURE, "noSigCryptoFile"
                                );
                            }
                            CryptoType cryptoType = new CryptoType(CryptoType.TYPE.ISSUER_SERIAL);
                            cryptoType.setIssuerSerial(
                                    ((X509IssuerSerial) x509obj).getIssuerName(),
                                    ((X509IssuerSerial) x509obj).getSerialNumber()
                            );
                            String alias = securityProperties.getSignatureVerificationCrypto().getAliasForX509Cert(((X509IssuerSerial) x509obj).getIssuerName(), ((X509IssuerSerial) x509obj).getSerialNumber());
                            if (alias == null) {
                                throw new WSSecurityException(
                                        WSSecurityException.FAILURE, "invalidSAMLsecurity",
                                        new Object[]{"cannot get certificate or key"}
                                );
                            }
                            certs = securityProperties.getSignatureVerificationCrypto().getCertificates(alias);
                            if (certs == null || certs.length < 1) {
                                throw new WSSecurityException(
                                        WSSecurityException.FAILURE, "invalidSAMLsecurity",
                                        new Object[]{"cannot get certificate or key"}
                                );
                            }
                            return new SAMLKeyInfo(certs);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            throw new WSSecurityException(
                    WSSecurityException.FAILURE, "invalidSAMLsecurity",
                    new Object[]{"cannot get certificate or key"}, ex
            );
        }
        return null;
    }

    /**
     * This method parses the KeyInfo of the Subject for the holder-of-key confirmation
     * method, as required by the SAML Token spec. It then stores the SAMLKeyInfo object that
     * has been obtained for future processing by the SignatureProcessor.
     *
     * @throws WSSecurityException
     */
    public SAMLKeyInfo parseHOKSubject(SecurityProperties securityProperties) throws WSSecurityException {
        String confirmMethod = null;
        List<String> methods = getConfirmationMethods();
        if (methods != null && methods.size() > 0) {
            confirmMethod = methods.get(0);
        }
        SAMLKeyInfo samlKeyInfo = null;
        if (OpenSAMLUtil.isMethodHolderOfKey(confirmMethod)) {

            if (saml2 != null) {
                samlKeyInfo = getCredentialFromSubject(saml2, securityProperties);
            } else if (saml1 != null) {
                samlKeyInfo = getCredentialFromSubject(saml1, securityProperties);
            }

            if (samlKeyInfo == null) {
                throw new WSSecurityException(WSSecurityException.FAILURE, "noKeyInSAMLToken");
            }
            // The assertion must have been signed for HOK
            if (!isSigned()) {
                throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity");
            }
        }
        return samlKeyInfo;
    }

    /**
     * Verify trust in the signature of a signed Assertion. This method is separate so that
     * the user can override if if they want.
     *
     * @param assertion The signed Assertion
     * @param data      The RequestData context
     * @return A Credential instance
     * @throws WSSecurityException
     */
    public void verifySignedAssertion(SAMLKeyInfo samlKeyInfo, SecurityProperties securityProperties) throws WSSecurityException {
        validate(samlKeyInfo.getCerts(), samlKeyInfo.getPublicKey(), securityProperties);
    }

    /**
     * Validate the credential argument. It must contain a non-null X509Certificate chain
     * or a PublicKey. A Crypto implementation is also required to be set.
     * <p/>
     * This implementation first attempts to verify trust on the certificate (chain). If
     * this is not successful, then it will attempt to verify trust on the Public Key.
     *
     * @param credential the Credential to be validated
     * @param data       the RequestData associated with the request
     * @throws WSSecurityException on a failed validation
     */
    protected void validate(X509Certificate[] certs, PublicKey publicKey, SecurityProperties securityProperties) throws WSSecurityException {
        Crypto crypto = securityProperties.getSignatureVerificationCrypto();
        if (crypto == null) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "noSigCryptoFile");
        }

        if (certs != null && certs.length > 0) {
            validateCertificates(certs);
            boolean trust = false;
            if (certs.length == 1) {
                trust = verifyTrustInCert(certs[0], crypto);
            } else {
                trust = verifyTrustInCerts(certs, crypto);
            }
            if (trust) {
                return;
            }
        }
        if (publicKey != null) {
            boolean trust = validatePublicKey(publicKey, crypto);
            if (trust) {
                return;
            }
        }
        throw new WSSecurityException(WSSecurityException.FAILED_AUTHENTICATION);
    }

    /**
     * Validate the certificates by checking the validity of each cert
     *
     * @throws WSSecurityException
     */
    //todo check if this method is already there in the framework
    protected void validateCertificates(X509Certificate[] certificates)
            throws WSSecurityException {
        try {
            for (int i = 0; i < certificates.length; i++) {
                certificates[i].checkValidity();
            }
        } catch (CertificateExpiredException e) {
            throw new WSSecurityException(
                    WSSecurityException.FAILED_CHECK, "invalidCert", null, e
            );
        } catch (CertificateNotYetValidException e) {
            throw new WSSecurityException(
                    WSSecurityException.FAILED_CHECK, "invalidCert", null, e
            );
        }
    }

    /**
     * Method getConfirmationMethods returns the confirmationMethods of this
     * AssertionWrapper object.
     *
     * @return the confirmationMethods of this AssertionWrapper object.
     */
    public List<String> getConfirmationMethods() {
        List<String> methods = new ArrayList<String>();
        if (saml2 != null) {
            org.opensaml.saml2.core.Subject subject = saml2.getSubject();
            List<org.opensaml.saml2.core.SubjectConfirmation> confirmations =
                    subject.getSubjectConfirmations();
            for (org.opensaml.saml2.core.SubjectConfirmation confirmation : confirmations) {
                methods.add(confirmation.getMethod());
            }
        } else if (saml1 != null) {
            List<SubjectStatement> subjectStatements = new ArrayList<SubjectStatement>();
            subjectStatements.addAll(saml1.getSubjectStatements());
            subjectStatements.addAll(saml1.getAuthenticationStatements());
            subjectStatements.addAll(saml1.getAttributeStatements());
            subjectStatements.addAll(saml1.getAuthorizationDecisionStatements());
            for (SubjectStatement subjectStatement : subjectStatements) {
                Subject subject = subjectStatement.getSubject();
                if (subject != null) {
                    SubjectConfirmation confirmation = subject.getSubjectConfirmation();
                    if (confirmation != null) {
                        XMLObject data = confirmation.getSubjectConfirmationData();
                        if (data instanceof ConfirmationMethod) {
                            ConfirmationMethod method = (ConfirmationMethod) data;
                            methods.add(method.getConfirmationMethod());
                        }
                        List<ConfirmationMethod> confirmationMethods =
                                confirmation.getConfirmationMethods();
                        for (ConfirmationMethod confirmationMethod : confirmationMethods) {
                            methods.add(confirmationMethod.getConfirmationMethod());
                        }
                    }
                }
            }
        }
        return methods;
    }

    /**
     * Get the SAMLKeyInfo object corresponding to the credential stored in the Subject of a
     * SAML 1.1 assertion
     *
     * @param assertion    The SAML 1.1 assertion
     * @param data         The RequestData instance used to obtain configuration
     * @param docInfo      A WSDocInfo instance
     * @param bspCompliant Whether to process tokens in compliance with the BSP spec or not
     * @return The SAMLKeyInfo object obtained from the Subject
     * @throws WSSecurityException
     */
    public SAMLKeyInfo getCredentialFromSubject(org.opensaml.saml1.core.Assertion assertion, SecurityProperties securityProperties) throws WSSecurityException {
        // First try to get the credential from a CallbackHandler
        WSPasswordCallback passwordCallback = new WSPasswordCallback(assertion.getID(), WSPasswordCallback.SECRET_KEY);
        Utils.dotSecretKeyCallback(securityProperties.getCallbackHandler(), passwordCallback, assertion.getID());
        final byte[] key = passwordCallback.getKey();
        if (key != null && key.length > 0) {
            return new SAMLKeyInfo(key);
        }

        for (org.opensaml.saml1.core.Statement stmt : assertion.getStatements()) {
            org.opensaml.saml1.core.Subject samlSubject = null;
            if (stmt instanceof org.opensaml.saml1.core.AttributeStatement) {
                org.opensaml.saml1.core.AttributeStatement attrStmt =
                        (org.opensaml.saml1.core.AttributeStatement) stmt;
                samlSubject = attrStmt.getSubject();
            } else if (stmt instanceof org.opensaml.saml1.core.AuthenticationStatement) {
                org.opensaml.saml1.core.AuthenticationStatement authStmt =
                        (org.opensaml.saml1.core.AuthenticationStatement) stmt;
                samlSubject = authStmt.getSubject();
            } else {
                org.opensaml.saml1.core.AuthorizationDecisionStatement authzStmt =
                        (org.opensaml.saml1.core.AuthorizationDecisionStatement) stmt;
                samlSubject = authzStmt.getSubject();
            }

            if (samlSubject == null) {
                throw new WSSecurityException(
                        WSSecurityException.FAILURE, "invalidSAMLToken",
                        new Object[]{"for Signature (no Subject)"}
                );
            }

            Element sub = samlSubject.getSubjectConfirmation().getDOM();
            Element keyInfoElement =
                    XMLUtils.getDirectChildElement(sub, Constants.TAG_dsig_KeyInfo.getLocalPart(), Constants.TAG_dsig_KeyInfo.getNamespaceURI());
            if (keyInfoElement != null) {
                return getCredentialFromKeyInfo(keyInfoElement, securityProperties);
            }
        }

        return null;
    }


    /**
     * Get the SAMLKeyInfo object corresponding to the credential stored in the Subject of a
     * SAML 2 assertion
     *
     * @param assertion    The SAML 2 assertion
     * @param data         The RequestData instance used to obtain configuration
     * @param docInfo      A WSDocInfo instance
     * @param bspCompliant Whether to process tokens in compliance with the BSP spec or not
     * @return The SAMLKeyInfo object obtained from the Subject
     * @throws WSSecurityException
     */
    public SAMLKeyInfo getCredentialFromSubject(org.opensaml.saml2.core.Assertion assertion, SecurityProperties securityProperties) throws WSSecurityException {
        // First try to get the credential from a CallbackHandler
        WSPasswordCallback passwordCallback = new WSPasswordCallback(assertion.getID(), WSPasswordCallback.SECRET_KEY);
        Utils.dotSecretKeyCallback(securityProperties.getCallbackHandler(), passwordCallback, assertion.getID());
        final byte[] key = passwordCallback.getKey();
        if (key != null && key.length > 0) {
            return new SAMLKeyInfo(key);
        }

        org.opensaml.saml2.core.Subject samlSubject = assertion.getSubject();
        if (samlSubject == null) {
            throw new WSSecurityException(
                    WSSecurityException.FAILURE, "invalidSAMLToken",
                    new Object[]{"for Signature (no Subject)"}
            );
        }
        List<org.opensaml.saml2.core.SubjectConfirmation> subjectConfList =
                samlSubject.getSubjectConfirmations();
        for (org.opensaml.saml2.core.SubjectConfirmation subjectConfirmation : subjectConfList) {
            SubjectConfirmationData subjConfData =
                    subjectConfirmation.getSubjectConfirmationData();
            Element sub = subjConfData.getDOM();
            Element keyInfoElement =
                    XMLUtils.getDirectChildElement(sub, Constants.TAG_dsig_KeyInfo.getLocalPart(), Constants.TAG_dsig_KeyInfo.getNamespaceURI());
            if (keyInfoElement != null) {
                return getCredentialFromKeyInfo(keyInfoElement, securityProperties);
            }
        }

        return null;
    }

    /**
     * Check to see if the certificate argument is in the keystore
     *
     * @param crypto The Crypto instance to use
     * @param cert   The certificate to check
     * @return true if cert is in the keystore
     * @throws WSSecurityException
     */
    protected boolean isCertificateInKeyStore(Crypto crypto, X509Certificate cert) throws WSSecurityException {
        String issuerString = cert.getIssuerX500Principal().getName();
        BigInteger issuerSerial = cert.getSerialNumber();

        String alias = crypto.getAliasForX509Cert(issuerString, issuerSerial);
        if (alias == null) {
            return false;
        }
        X509Certificate[] foundCerts = crypto.getCertificates(alias);

        //
        // If a certificate has been found, the certificates must be compared
        // to ensure against phony DNs (compare encoded form including signature)
        //
        if (foundCerts != null && foundCerts[0] != null && foundCerts[0].equals(cert)) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "Direct trust for certificate with " + cert.getSubjectX500Principal().getName()
                );
            }
            return true;
        }
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "No certificate found for subject from issuer with " + issuerString
                            + " (serial " + issuerSerial + ")"
            );
        }
        return false;
    }

    /**
     * Evaluate whether a given certificate should be trusted.
     * <p/>
     * Policy used in this implementation:
     * 1. Search the keystore for the transmitted certificate
     * 2. Search the keystore for a connection to the transmitted certificate
     * (that is, search for certificate(s) of the issuer of the transmitted certificate
     * 3. Verify the trust path for those certificates found because the search for the issuer
     * might be fooled by a phony DN (String!)
     *
     * @param cert   the certificate that should be validated against the keystore
     * @param crypto A crypto instance to use for trust validation
     * @return true if the certificate is trusted, false if not
     * @throws WSSecurityException
     */
    //todo check if this method is already there in the framework
    //todo check also if this is the better implementation
    protected boolean verifyTrustInCert(X509Certificate cert, Crypto crypto)
            throws WSSecurityException {
        String subjectString = cert.getSubjectX500Principal().getName();
        String issuerString = cert.getIssuerX500Principal().getName();
        BigInteger issuerSerial = cert.getSerialNumber();

        if (logger.isDebugEnabled()) {
            logger.debug("Transmitted certificate has subject " + subjectString);
            logger.debug(
                    "Transmitted certificate has issuer " + issuerString + " (serial "
                            + issuerSerial + ")"
            );
        }

        //
        // FIRST step - Search the keystore for the transmitted certificate
        //
        if (isCertificateInKeyStore(crypto, cert)) {
            return true;
        }

        //
        // SECOND step - Search for the issuer cert (chain) of the transmitted certificate in the
        // keystore or the truststore
        //
        String alias = crypto.getAliasForX509Cert(issuerString);
        if (alias == null) {
            return false;
        }
        X509Certificate[] foundCerts = crypto.getCertificates(alias);

        // If the certs have not been found, the issuer is not in the keystore/truststore
        // As a direct result, do not trust the transmitted certificate
        if (foundCerts == null || foundCerts.length < 1) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "No certs found in keystore for issuer " + issuerString
                                + " of certificate for " + subjectString
                );
            }
            return false;
        }

        //
        // THIRD step
        // Check the certificate trust path for the issuer cert chain
        //
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Preparing to validate certificate path for issuer " + issuerString
            );
        }
        //
        // Form a certificate chain from the transmitted certificate
        // and the certificate(s) of the issuer from the keystore/truststore
        //
        X509Certificate[] x509certs = new X509Certificate[foundCerts.length + 1];
        x509certs[0] = cert;
        for (int j = 0; j < foundCerts.length; j++) {
            x509certs[j + 1] = (X509Certificate) foundCerts[j];
        }

        //
        // Use the validation method from the crypto to check whether the subjects'
        // certificate was really signed by the issuer stated in the certificate
        //
        if (crypto.verifyTrust(x509certs)) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "Certificate path has been verified for certificate with subject "
                                + subjectString
                );
            }
            return true;
        }

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Certificate path could not be verified for certificate with subject "
                            + subjectString
            );
        }
        return false;
    }

    /**
     * Evaluate whether the given certificate chain should be trusted.
     *
     * @param certificates the certificate chain that should be validated against the keystore
     * @return true if the certificate chain is trusted, false if not
     * @throws WSSecurityException
     */
    protected boolean verifyTrustInCerts(X509Certificate[] certificates, Crypto crypto)
            throws WSSecurityException {
        String subjectString = certificates[0].getSubjectX500Principal().getName();
        //
        // Use the validation method from the crypto to check whether the subjects'
        // certificate was really signed by the issuer stated in the certificate
        //
        if (certificates != null && certificates.length > 1
                && crypto.verifyTrust(certificates)) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "Certificate path has been verified for certificate with subject "
                                + subjectString
                );
            }
            return true;
        }

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Certificate path could not be verified for certificate with subject "
                            + subjectString
            );
        }

        return false;
    }

    /**
     * Validate a public key
     *
     * @throws WSSecurityException
     */
    protected boolean validatePublicKey(PublicKey publicKey, Crypto crypto)
            throws WSSecurityException {
        return crypto.verifyTrust(publicKey);
    }
}