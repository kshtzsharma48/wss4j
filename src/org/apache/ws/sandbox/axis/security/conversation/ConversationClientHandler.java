/*
 * Copyright  2003-2004 The Apache Software Foundation.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.ws.axis.security.conversation;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Stack;
import java.util.Vector;

import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPHeader;

import org.apache.axis.AxisFault;
import org.apache.axis.Message;
import org.apache.axis.MessageContext;
import org.apache.axis.SOAPPart;
import org.apache.axis.components.logger.LogFactory;
import org.apache.axis.handlers.BasicHandler;
import org.apache.axis.message.MessageElement;
import org.apache.axis.message.SOAPHeaderElement;
import org.apache.axis.types.URI;
import org.apache.axis.types.URI.MalformedURIException;
import org.apache.commons.logging.Log;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityEngine;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.CryptoFactory;
import org.apache.ws.security.conversation.ConvEngineResult;
import org.apache.ws.security.conversation.ConversationConstants;
import org.apache.ws.security.conversation.ConversationEngine;
import org.apache.ws.security.conversation.ConversationException;
import org.apache.ws.security.conversation.ConversationManager;
import org.apache.ws.security.conversation.ConversationUtil;
import org.apache.ws.security.conversation.DerivedKeyCallbackHandler;
import org.apache.ws.security.conversation.message.info.DerivedKeyInfo;
import org.apache.ws.security.conversation.message.info.SecurityContextInfo;
import org.apache.ws.security.conversation.message.token.RequestSecurityTokenResponse;
import org.apache.ws.security.conversation.message.token.RequestedProofToken;
import org.apache.ws.security.conversation.message.token.RequestedSecurityToken;
import org.apache.ws.security.conversation.message.token.SecurityContextToken;
import org.apache.ws.security.message.token.SecurityTokenReference;
//import org.apache.ws.security.trust.TrustCommunicator;
import org.apache.ws.security.trust.TrustConstants;
import org.apache.ws.security.trust.message.token.TokenType;
import org.apache.ws.security.util.StringUtil;
import org.apache.ws.security.util.WSSecurityUtil;
import org.apache.xml.security.utils.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 *
 * This handler performs the client side actions, in order to execute WS-Secure
 * Conversation. It employs three major components;
 * <br /> 1) DerivedKeyCallbackHandler.java - Interfacing to the derived key generation component.
 * <br /> 2) ConversationEngine.java - Process and validate conversation elements.
 * <br /> 3) ConversationClient.java - Creates conversation elements.
 * 
 * @author Dimuthu Leelarathne. (muthulee@yahoo.com)
 * @author Kaushalye Kapruge.  (kaushalye@yahoo.com)
 *
 */

public class ConversationClientHandler extends BasicHandler {
    private static Log log =
        LogFactory.getLog(ConversationClientHandler.class.getName());

    private int requestCount = 0;
    private RequestSecurityTokenResponse stRes;

    private DerivedKeyCallbackHandler dkcbHandler =
        new DerivedKeyCallbackHandler();

    // private int frequency = 1;
    private WSSecurityEngine secEng = null;
    private String uuid = null;

    private Crypto serverCrypto = null;
    private String serverAlias = null;
    private Crypto reqCrypto = null;
    private Crypto stsCrypto = null;

    private int sctEstablishment = -1;

    private static boolean handShakeDone = false;
    private boolean isSCTavailabe = false;
    private static boolean isConfigured = false;
    private boolean readCrypto = false;

	private String appliesTo = null; 
    private HashMap configurator;

    int[] actionsInt;
    static {
        org.apache.xml.security.Init.init();
    }

    public ConversationClientHandler() throws AxisFault {
        log.debug("ConversationClientHandler :: created");

    }

    /**
     * Method inherited from the BasicHandler.
     * If in the request flow calls the doRequestMetod()
     * else calls the doResponse() method. 
     * 
     */
    public void invoke(MessageContext msg) throws AxisFault {
        log.debug("ConversationClientHandler :: invoked");
       if (msg.getPastPivot())
            doResponse(msg);
        else
            doRequest(msg);
    }

    /**
     * The method is called in the request flow.
     * 
     * Do request method behaves in two different was according to the fact that
     * <p>initial handshake is done.</p>
     * <p>OR</p>
     * <p>initial handshake is not done, i.e. SCT is not in memory</p>
     *
     * <br/>If SCT is in memory(handshake is done), then conversation carried out 
     * using it
     * <br/>If Token is not in memory (handshake is not done), the the SCT generation
     * method will be read from the wsdd file. According to the parameters read the 
     * method will execute actions. 
     * @param msg
     * @throws AxisFault
     */

    private void doRequest(MessageContext msg) throws AxisFault {

        Integer tempInt;

        Message sm = msg.getCurrentMessage();
        //SOAPPart sPart = (org.apache.axis.SOAPPart) sm.getSOAPPart();
        Document doc = null;

        if (!handShakeDone) {

            decodeSCTEstabParameter();
			this.loadCrypto();
           
            switch (this.sctEstablishment) {

                case ConversationConstants.DIRECT_GENERATED :
                    this.doHandshake_Direct_Generated(sm);
                    break;

                case ConversationConstants.STS_GENERATED :
                    this.doHandshake_STS_Generated(sm);
                    break;

                case ConversationConstants.WS_GENERATED :
                    break;
                default :
                    throw new AxisFault("Unsupored STS establishment method.");

            }

            handShakeDone = true;

        } else { // handshake is done.

            log.debug("Token in memory .");
            SOAPPart sPart = (org.apache.axis.SOAPPart) sm.getSOAPPart();
            try {
                doc =
                    ((org.apache.axis.message.SOAPEnvelope) sPart
                        .getEnvelope())
                        .getAsDocument();
            } catch (Exception e) {
                throw new AxisFault("CoversationClientHandler :: Cannot get the document");
            }

            try {

                //				add the relavent SCT
                Element securityHeader =
                    WSSecurityUtil.findWsseSecurityHeaderBlock(WSSConfig.getDefaultWSConfig(),
                        doc,
                        doc.getDocumentElement(),
                        true);
                WSSecurityUtil.appendChildElement(
                    doc,
                    securityHeader,
                    (new SecurityContextToken(doc, uuid)).getElement());
                ConversationManager manager = new ConversationManager();
                for (int i = 0; i < this.actionsInt.length; i++) {
                    // Derrive the token
                    DerivedKeyInfo dkInfo =
                        manager.addDerivedKeyToken(doc, uuid, dkcbHandler);

                    String genID = dkInfo.getId();
                    SecurityTokenReference stRef =
                        dkInfo.getSecTokRef2DkToken();
                    if (actionsInt[i] == ConversationConstants.DK_ENCRYPT) {
                        manager.performDK_ENCR(
                            ConversationUtil.generateIdentifier(uuid, genID),
                            "",
                            true,
                            doc,
                            stRef,
                            dkcbHandler);
                    } else if(actionsInt[i]==ConversationConstants.DK_SIGN){
                        manager.performDK_Sign(doc, dkcbHandler, uuid, dkInfo);
                    }

                }
            } catch (ConversationException e1) {
                e1.printStackTrace();
                throw new AxisFault(
                    "ConversationClientHandler ::" + e1.getMessage());
            }

            //set it as current message
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            XMLUtils.outputDOM(doc, os, true);
            String osStr = os.toString();
            sPart.setCurrentMessage(osStr, SOAPPart.FORM_STRING);

        }

    }
    /**
    * This method is called in the response. 
    * If Security Context Token (SCT) is not in the message, then it throws a fault.
    *
    * @param msgContext
    * @throws AxisFault
    */
    private void doResponse(MessageContext msgContext)
        throws AxisFault { //for incoming message
        Document doc = null;

        Message message = msgContext.getCurrentMessage();
        SOAPPart sPart = (org.apache.axis.SOAPPart) message.getSOAPPart();
//        if (!this.readCrypto) {
//            this.loadCrypto();
//        }
        try {
            doc =
                ((org.apache.axis.message.SOAPEnvelope) sPart.getEnvelope())
                    .getAsDocument();

        } catch (Exception e) {
            throw new AxisFault(
                "WSDoAllSender: cannot get SOAP envlope from message" + e);
        }

        /*Get the derved key tokens.
         *Add them to the convSession.
         */

        if ((this.configurator =
            (HashMap) msgContext.getProperty("PolicyObject"))
            == null) {
            initSessionInfo();
            // load values to this.configurator from wsdd
        }
        
        
        try{
        ConversationEngine convEng = new ConversationEngine(this.configurator);
		Vector results = convEng.processSecConvHeader(doc, "", dkcbHandler);
	    ConvEngineResult convResult  = null;
		//String uuid = "";
        
        
        
		/*put the actions into a stack to obtain LIFO behavior
					 * Rational for using the stack;
					 * 
					 * Consider "Signature Encrypt" 
					 * Then the ConvEngine Results will be in the order "Encrypt Signature"
					 * i.e. ConvEngine reusult containing ConvEngineResult.ENCRYPT_DERIVED_KEY
					 * will be before ConvEngineResult.SIGN_DERIVED_KEY
					 * 
					 * Hense I need to read actions in the order of Last in First out - the stack 
					 * 
					 * This is same for "Encrypt Signature" visa versa.
					 */
					Stack stk = new Stack();
					for(int i=0; i<actionsInt.length ; i++){
						stk.push(new Integer(actionsInt[i]));
					}
					int act = -1;
					boolean rstr = false;
					for(int i=0; i<results.size(); i++){
						convResult=(ConvEngineResult)results.get(i);
						
						switch(convResult.getAction()){
				
						case ConvEngineResult.SECURITY_TOKEN_RESPONSE :
						log.debug("ConversationServerHandler :: Found RSTR result");
						uuid = convResult.getUuid();
						rstr = true;
						break;
				
						case ConvEngineResult.ENCRYPT_DERIVED_KEY :
						log.debug("ConversationServerHandler :: Found dk_encrypt result"); 				
							if(stk.isEmpty()){
								throw new AxisFault("Action mismatch");
							}
				    
							act =((Integer)stk.pop()).intValue();
							if(act == ConversationConstants.DK_ENCRYPT){
								//fine do nothing
							}else{
								throw new AxisFault("Mismatch action order");
							}
						break;
				
						case ConvEngineResult.SIGN_DERIVED_KEY :
						log.debug("ConversationServerHandler :: Found dk_sign result");
							if(stk.isEmpty()){
								throw new AxisFault("Action mismatch");
							}
							act =((Integer)stk.pop()).intValue();
							if(act == ConversationConstants.DK_SIGN){
								//fine do nothing
							}else{
								throw new AxisFault("Mismatch action order");
							}
						break;
				
						case ConvEngineResult.SCT :
						log.debug("ConversationServerHandler :: Found SCT result");
						uuid = convResult.getUuid();
						break;
				
						}
						}
			
					if(uuid.equals("")){
						throw new AxisFault("ConversationServerHandler :: Cannot find Session.");
					}
		    
					if(!rstr){
					if(!stk.isEmpty()){
					  throw new AxisFault("Action mismatch. Required action missing");
					}
					}
		//			msgContext.setProperty(ConversationConstants.IDENTIFIER,uuid);
        
        
        
        
				} catch (ConversationException e1) {
					e1.printStackTrace();
					throw new AxisFault("CovnersationServerHandler :: "+e1.getMessage());
				}

          
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        XMLUtils.outputDOM(doc, os, true);
        sPart.setCurrentMessage(os.toByteArray(), SOAPPart.FORM_BYTES);

    } //do response done

    /**
     * The method is responsible for generating a SCT. This implements the scenario
     * described in the specification as "Security context token created by 
     * one of the communicating parties and propagated with a message"
     * 
     * @param sm
     * @throws AxisFault
     */
    private void doHandshake_Direct_Generated(Message sm) throws AxisFault {
        Document doc = null;
        SOAPPart sPart = (org.apache.axis.SOAPPart) sm.getSOAPPart();
        try {

            log.debug("ConversationClientHandler :: Trust Not required");
            doc =
                ((org.apache.axis.message.SOAPEnvelope) sPart.getEnvelope())
                    .getAsDocument();
            this.initSessionInfo();
            this.stRes = new RequestSecurityTokenResponse(doc, true);

        } catch (Exception e) {
            e.printStackTrace();
            throw new AxisFault(
                "ConversationClientHandler ::" + e.getMessage());
        }

        /*
         * SCT is now created.
         * Steps::
         * 1)
         * 2)SCTInfo in dkcbHandler
         */
        uuid = stRes.getRequestedSecurityToken().getSct().getIdentifier();

        stRes.build(doc);
        isSCTavailabe = true;

        //Now encrypting with the base token
        RequestedProofToken reqProof = stRes.getRequestedProofToken();

        try {
            reqProof.doEncryptProof(doc, this.serverCrypto, this.serverAlias);

            SecurityContextInfo info =
                new SecurityContextInfo(
                    stRes.getRequestedSecurityToken().getSct(),
                    reqProof,
                    ((Integer) (configurator
                        .get(ConvHandlerConstants.KEY_FREQ)))
                        .intValue());

            dkcbHandler.addSecurtiyContext(uuid, info);
            /*
             * Add session specific information to the dkcbHandler
             * 1) Key frequency.
             */
            if (((Boolean) configurator
                .get(ConvHandlerConstants.USE_FIXED_KEYLEN))
                .booleanValue()) {
                dkcbHandler.setDerivedKeyLength(
                    uuid,
                    ((Long) configurator.get(ConvHandlerConstants.KEY_LEGNTH))
                        .longValue());
            }
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            XMLUtils.outputDOM(doc, os, true);
            String osStr = os.toString();
            sPart.setCurrentMessage(osStr, SOAPPart.FORM_STRING);
        } catch (WSSecurityException e2) {
            e2.printStackTrace();
            throw new AxisFault(
                "ConversationClientHandler ::" + e2.getMessage());
        } catch (ConversationException e2) {
            e2.printStackTrace();
            throw new AxisFault(
                "ConversationClientHandler ::" + e2.getMessage());
        }

    }

    /**
     * This method is repsonsible for obtaining the SCT from the STS.
     * 
     * Firstly, a method call to the STS is done, usig WS-Trust components.  
     * 
     * The STS will return a <RequestedSecurityTokenResponse> that contains 
     * <RequestedProofToken> and <RequestedSecurityToken>
     * 
     * The returned <RequestedProofToken> is decrypted, and again encrypted with the servers
     * certificate to create a new  <RequestedProofToken>.
     * 
     * The recieved <RequestedSecurityToken> and the newly created <RequestedProofToken> is
     * added to the message.
     *    
     *
     * @param sm
     * @throws AxisFault
     */
    private void doHandshake_STS_Generated(Message sm) throws AxisFault {
        Document doc = null;
        MessageElement[] meArrRes = null;
        String tmpStr = null;
		String stsEndPoint, callbackHandler;
		
		
		if ((tmpStr = (String) getOption(ConvHandlerConstants.STS_ADDRESS))
			!= null) {
				stsEndPoint =tmpStr;	    
		}else{
			throw new AxisFault("STS address is not set.");
		}        
        
        if ((tmpStr =(String) getOption(ConvHandlerConstants.APPLIES_TO_VAL))
						!= null) {
							log.debug("Applies to value is read ::" + tmpStr);
				this.appliesTo = tmpStr;			
			}
		
		if ((tmpStr = (String) getOption(ConvHandlerConstants.CONV_CALLBACK))
					!= null) {
						callbackHandler =tmpStr;	    
		}else{
					throw new AxisFault("PasswordCallbackHandler is not set.");
		}
			
//        try {
////            TrustCommunicator tc =
////                new TrustCommunicator(stsEndPoint);
////            
////            tc.requestSecurityToken(
////                new URI(TrustConstants.ISSUE_SECURITY_TOKEN_RST),
////                TokenType.SCT,this.appliesTo);
////            
////            meArrRes = tc.getResponse();
////            log.debug(
////                "TrustCommTester end length of elements in the response is "
////                    + meArrRes.length);
//
//        } catch (MalformedURIException e1) {
//            e1.printStackTrace();
//            throw new AxisFault(
//                "ConversationClientHandler ::" + e1.getMessage());
//        } catch (Exception e1) {
//            e1.printStackTrace();
//            throw new AxisFault(
//                "ConversationClientHandler ::" + e1.getMessage());
//        }

        // We have successfully recieved the message element part.
        SecurityContextToken sct = null;
        RequestedProofToken proof = null;

        log.debug("Trust communitcator successfully completed.");
        try {
            MessageElement tmpEle = null;
            for (int i = 0; i < meArrRes.length; i++) {
                tmpEle = meArrRes[i];
                QName el =
                    new QName(tmpEle.getNamespaceURI(), tmpEle.getLocalName());

                Element domEle = tmpEle.getAsDOM();

                if (el.equals(RequestedSecurityToken.TOKEN)) {
                    log.debug("Recognized RequestedSecurityToken.");

                    NodeList ndList =
                        domEle.getElementsByTagNameNS(
                            SecurityContextToken.TOKEN.getNamespaceURI(),
                            SecurityContextToken.TOKEN.getLocalPart());
                    if (ndList.getLength() < 0) {
                        throw new AxisFault("Unspported yet ..");
                    }
                    sct = new SecurityContextToken((Element) ndList.item(0));

                    SOAPHeader soapHeader = sm.getSOAPHeader();
                    soapHeader.addChildElement(
                        "Security",
                        WSConstants.WSSE_PREFIX,
                        WSConstants.WSSE_NS);

                    Iterator it = soapHeader.getChildElements();
                    while (it.hasNext()) {
                        SOAPHeaderElement shSecElem;
                        if ((shSecElem = (SOAPHeaderElement) it.next())
                            .getLocalName()
                            .equals("Security")) {
                            MessageElement rstr =
                                new MessageElement(
                                    RequestSecurityTokenResponse
                                        .TOKEN
                                        .getLocalPart(),
                                    RequestSecurityTokenResponse
                                        .TOKEN
                                        .getPrefix(),
                                    RequestSecurityTokenResponse
                                        .TOKEN
                                        .getNamespaceURI());
                            rstr.addChild(tmpEle);
                            shSecElem.addChildElement(rstr);
                        }
                    }
                } else if (el.equals(RequestedProofToken.TOKEN)) {
                    SOAPPart sPart =
                        (org.apache.axis.SOAPPart) sm.getSOAPPart();
                    doc =
                        ((org.apache.axis.message.SOAPEnvelope) sPart
                            .getEnvelope())
                            .getAsDocument();
                    //do decrytion - proof is encrypted with certificate of STS 
                    proof = new RequestedProofToken(domEle);
             
             
                    proof.doDecryption(callbackHandler, serverCrypto);

                    byte[] bkArr = proof.getSharedSecret();
                    RequestedProofToken newProof = new RequestedProofToken(doc);
                    newProof.setSharedSecret(bkArr);
                    newProof.doEncryptProof(
                        doc,
                        serverCrypto,
                        this.serverAlias);

                    Element secHeader =
                        WSSecurityUtil.findWsseSecurityHeaderBlock(WSSConfig.getDefaultWSConfig(),
                            doc,
                            doc.getDocumentElement(),
                            true);

                    Element ele =
                        (Element) WSSecurityUtil.findElement(
                            secHeader,
                            RequestSecurityTokenResponse.TOKEN.getLocalPart(),
                            RequestSecurityTokenResponse
                                .TOKEN
                                .getNamespaceURI());

                    ele.appendChild(newProof.getElement());

                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    XMLUtils.outputDOM(doc, os, true);
                    String osStr = os.toString();
                    sPart.setCurrentMessage(osStr, SOAPPart.FORM_STRING);

                }

            } //for loop

            this.initSessionInfo();
            Integer keyFreq =
                (Integer) this.configurator.get(ConvHandlerConstants.KEY_FREQ);
            SecurityContextInfo sctInfo =
                new SecurityContextInfo(sct, proof, keyFreq.intValue());
            this.uuid = sct.getIdentifier();
            dkcbHandler.addSecurtiyContext(uuid, sctInfo);

            Boolean isFixedKey =
                (Boolean) configurator.get(
                    ConvHandlerConstants.USE_FIXED_KEYLEN);

            if (isFixedKey.booleanValue()) {
                Long keyLen =
                    (Long) this.configurator.get(
                        ConvHandlerConstants.KEY_LEGNTH);
                dkcbHandler.setDerivedKeyLength(uuid, keyLen.longValue());
            }

            handShakeDone = true;

        } catch (WSSecurityException e3) {
            e3.printStackTrace();
            throw new AxisFault(
                "ConversationClientHandler ::" + e3.getMessage());
        } catch (SOAPException e) {
            e.printStackTrace();
            throw new AxisFault(
                "ConversationClientHandler ::" + e.getMessage());
        } catch (Exception e3) {
            e3.printStackTrace();
            throw new AxisFault(
                "ConversationClientHandler ::" + e3.getMessage());
        }

    } //end of doHandshake_STS_Generated

    /**
     * Reads configeration parameters from the wsdd file.
     * @throws AxisFault
     */
    private void initSessionInfo() throws AxisFault {
        /**
         * Default values for a session. These will be overriden by WSDD file parameters.
         */
        this.configurator = new HashMap();
        String tmpStr;
        if ((tmpStr = (String) getOption(ConvHandlerConstants.KEY_FREQ))
            != null) {
            log.debug("Key Frequency is set ::" + tmpStr);
            this.configurator.put(
                ConvHandlerConstants.KEY_FREQ,
                new Integer(tmpStr));
        }

        if ((tmpStr = (String) getOption(ConvHandlerConstants.DK_ACTION))
            != null) {
            log.debug("Derived Key Action is read ::" + tmpStr);
            String[] action = StringUtil.split(tmpStr, ' ');
            actionsInt = new int[action.length];

            for (int i = 0; i < action.length; i++) {
                if ((action[i]).equalsIgnoreCase("Signature")) {
                    actionsInt[i] = ConversationConstants.DK_SIGN;
                } else if ((action[i]).equalsIgnoreCase("Encrypt")) {
                    actionsInt[i] = ConversationConstants.DK_ENCRYPT;
                }
            }
        }
        
	
        
        if ((tmpStr =
            (String) getOption(ConvHandlerConstants.USE_FIXED_KEYLEN))
            != null) {
            log.debug("Boolean FixedKeyLegnth is set ::" + tmpStr);

            Boolean fixed = new Boolean(tmpStr);
            this.configurator.put(ConvHandlerConstants.USE_FIXED_KEYLEN, fixed);

            if (fixed.booleanValue()) {
                //Following has to be specified.
                if ((tmpStr =
                    (String) getOption(ConvHandlerConstants.KEY_LEGNTH))
                    != null) {

                    log.debug("Key Frequency is set ::" + tmpStr);
                    this.configurator.put(
                        ConvHandlerConstants.KEY_LEGNTH,
                        new Long(tmpStr));

                } else {
                    throw new AxisFault("If fixed keys are set then set the key legnth too.");
                }

            } else {
                // TODO :: add all the "MUST" parameters for variable keys
            }
        }

    }

    /**
     * Decodes the SCT establishment parameter set in the .wsdd
     * @throws AxisFault
     */
    private void decodeSCTEstabParameter() throws AxisFault {

        String tmpStr =
            (String) getOption(ConvHandlerConstants.SCT_ESTABLISH_MTD);
        log.debug(
            "ConversationClientHandler :: Decording SCT establishing parameter");
        if (tmpStr.equals(null)) {
            throw new AxisFault("SCT establishing method not specified.");
        } else {
            Integer i =
                (Integer) ConvHandlerConstants.sctEstablishmentMapper.get(
                    tmpStr);
            this.sctEstablishment = i.intValue();
        }
    }

    /**
     * Loads the crypto property files
     * @throws AxisFault
     */
    private void loadCrypto() throws AxisFault {
        String tmpStr = null;

        if ((tmpStr = (String) getOption(ConvHandlerConstants.SEVER_PROP_FILE))
            == null) {
            throw new AxisFault("Error! No server server properties file in wsdd");
        }

        log.debug("Server prop file is " + tmpStr);

        this.serverCrypto = CryptoFactory.getInstance(tmpStr);

        if ((tmpStr = (String) getOption(ConvHandlerConstants.SEVER_ALIAS))
            == null) {
            throw new AxisFault("Error! No server server properties file in wsdd");
        }
        this.serverAlias = tmpStr;

        if ((tmpStr =
            (String) getOption(ConvHandlerConstants.REQUESTOR_PROP_FILE))
            == null) {
            throw new AxisFault("Error! No reqestor properties file in wsdd");
        }

        log.debug("Requestor prop file is " + tmpStr);
        this.reqCrypto = CryptoFactory.getInstance(tmpStr);

    }

    private void decodeDkAction() {

    }

}