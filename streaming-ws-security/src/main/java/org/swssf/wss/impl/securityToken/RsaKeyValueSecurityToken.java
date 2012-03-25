/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.swssf.wss.impl.securityToken;

import org.swssf.binding.xmldsig.RSAKeyValueType;
import org.swssf.wss.ext.WSSConstants;
import org.swssf.wss.ext.WSSecurityContext;
import org.swssf.wss.ext.WSSecurityException;
import org.swssf.xmlsec.crypto.Crypto;
import org.swssf.xmlsec.ext.SecurityToken;
import org.swssf.xmlsec.ext.XMLSecurityConstants;
import org.swssf.xmlsec.ext.XMLSecurityException;

import javax.security.auth.callback.CallbackHandler;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;

/**
 * @author $Author$
 * @version $Revision$ $Date$
 */
public class RsaKeyValueSecurityToken extends AbstractSecurityToken {

    private PublicKey publicKey;

    public RsaKeyValueSecurityToken(RSAKeyValueType rsaKeyValueType, WSSecurityContext wsSecurityContext, Crypto crypto, CallbackHandler callbackHandler,
                                    WSSConstants.KeyIdentifierType keyIdentifierType) throws XMLSecurityException {
        super(wsSecurityContext, crypto, callbackHandler, null, keyIdentifierType);

        try {
            this.publicKey = buildPublicKey(rsaKeyValueType);
        } catch (InvalidKeySpecException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.INVALID_SECURITY_TOKEN, e);
        } catch (NoSuchAlgorithmException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.INVALID_SECURITY_TOKEN, e);
        }
    }

    private PublicKey buildPublicKey(RSAKeyValueType rsaKeyValueType) throws InvalidKeySpecException, NoSuchAlgorithmException {
        RSAPublicKeySpec rsaPublicKeySpec = new RSAPublicKeySpec(
                new BigInteger(rsaKeyValueType.getModulus()),
                new BigInteger(rsaKeyValueType.getExponent()));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(rsaPublicKeySpec);
    }

    @Override
    protected Key getKey(String algorithmURI, XMLSecurityConstants.KeyUsage keyUsage) throws XMLSecurityException {
        return null;
    }

    @Override
    protected PublicKey getPubKey(String algorithmURI, XMLSecurityConstants.KeyUsage keyUsage) throws XMLSecurityException {
        return this.publicKey;
    }

    @Override
    public boolean isAsymmetric() {
        return true;
    }

    @Override
    public XMLSecurityConstants.TokenType getTokenType() {
        return WSSConstants.KeyValueToken;
    }

    //todo move to super class?
    @Override
    public SecurityToken getKeyWrappingToken() throws XMLSecurityException {
        return null;
    }
}
