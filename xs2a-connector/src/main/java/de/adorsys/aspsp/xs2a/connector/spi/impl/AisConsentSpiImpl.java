/*
 * Copyright 2018-2019 adorsys GmbH & Co KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.adorsys.aspsp.xs2a.connector.spi.impl;

import de.adorsys.aspsp.xs2a.connector.spi.converter.AisConsentMapper;
import de.adorsys.aspsp.xs2a.connector.spi.converter.ScaLoginMapper;
import de.adorsys.aspsp.xs2a.connector.spi.converter.ScaMethodConverter;
import de.adorsys.ledgers.middleware.api.domain.sca.OpTypeTO;
import de.adorsys.ledgers.middleware.api.domain.sca.SCAConsentResponseTO;
import de.adorsys.ledgers.middleware.api.domain.sca.SCALoginResponseTO;
import de.adorsys.ledgers.middleware.api.domain.sca.SCAResponseTO;
import de.adorsys.ledgers.middleware.api.domain.um.AisConsentTO;
import de.adorsys.ledgers.middleware.api.domain.um.BearerTokenTO;
import de.adorsys.ledgers.middleware.api.domain.um.ScaUserDataTO;
import de.adorsys.ledgers.middleware.api.service.TokenStorageService;
import de.adorsys.ledgers.rest.client.AuthRequestInterceptor;
import de.adorsys.ledgers.rest.client.ConsentRestClient;
import de.adorsys.psd2.xs2a.core.consent.AspspConsentData;
import de.adorsys.psd2.xs2a.core.consent.ConsentStatus;
import de.adorsys.psd2.xs2a.core.error.MessageErrorCode;
import de.adorsys.psd2.xs2a.core.error.TppMessage;
import de.adorsys.psd2.xs2a.spi.domain.SpiAspspConsentDataProvider;
import de.adorsys.psd2.xs2a.spi.domain.SpiContextData;
import de.adorsys.psd2.xs2a.spi.domain.account.SpiAccountConsent;
import de.adorsys.psd2.xs2a.spi.domain.authorisation.*;
import de.adorsys.psd2.xs2a.spi.domain.consent.SpiInitiateAisConsentResponse;
import de.adorsys.psd2.xs2a.spi.domain.consent.SpiVerifyScaAuthorisationResponse;
import de.adorsys.psd2.xs2a.spi.domain.psu.SpiPsuData;
import de.adorsys.psd2.xs2a.spi.domain.response.SpiResponse;
import de.adorsys.psd2.xs2a.spi.domain.response.SpiResponse.VoidResponse;
import de.adorsys.psd2.xs2a.spi.service.AisConsentSpi;
import feign.FeignException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static de.adorsys.ledgers.middleware.api.domain.sca.ScaStatusTO.*;
import static de.adorsys.psd2.xs2a.core.error.MessageErrorCode.*;
import static java.lang.String.format;

@Component
public class AisConsentSpiImpl implements AisConsentSpi {
    private static final Logger logger = LoggerFactory.getLogger(AisConsentSpiImpl.class);
    private static final String USER_LOGIN = "{userLogin}";
    private static final String CONSENT_ID = "{consentId}";
    private static final String AUTH_ID = "{authorizationId}";
    private static final String TAN = "{tan}";
    private static final String DECOUPLED_USR_MSG = "Please check your app to continue... %s";

    private static final String SCA_STATUS_LOG = "SCA status is {}";
    private static final String DECOUPLED_NOT_SUPPORTED_MESSAGE = "Service is not supported";

    private final ConsentRestClient consentRestClient;
    private final TokenStorageService tokenStorageService;
    private final AisConsentMapper aisConsentMapper;
    private final AuthRequestInterceptor authRequestInterceptor;
    private final AspspConsentDataService consentDataService;
    private final GeneralAuthorisationService authorisationService;
    private final ScaMethodConverter scaMethodConverter;
    private final ScaLoginMapper scaLoginMapper;
    private final FeignExceptionReader feignExceptionReader;

    @Value("${online-banking.url}")
    private String onlineBankingUrl;

    public AisConsentSpiImpl(ConsentRestClient consentRestClient, TokenStorageService tokenStorageService,
                             AisConsentMapper aisConsentMapper, AuthRequestInterceptor authRequestInterceptor,
                             AspspConsentDataService consentDataService, GeneralAuthorisationService authorisationService,
                             ScaMethodConverter scaMethodConverter, ScaLoginMapper scaLoginMapper, FeignExceptionReader feignExceptionReader) {
        this.consentRestClient = consentRestClient;
        this.tokenStorageService = tokenStorageService;
        this.aisConsentMapper = aisConsentMapper;
        this.authRequestInterceptor = authRequestInterceptor;
        this.consentDataService = consentDataService;
        this.authorisationService = authorisationService;
        this.scaMethodConverter = scaMethodConverter;
        this.scaLoginMapper = scaLoginMapper;
        this.feignExceptionReader = feignExceptionReader;
    }

    /*
     * Initiates an ais consent. Initiation request assumes that the psu id at least
     * identified. THis is, we read a {@link SCAResponseTO} object from the {@link AspspConsentData} input.
     */
    @Override
    public SpiResponse<SpiInitiateAisConsentResponse> initiateAisConsent(@NotNull SpiContextData contextData,
                                                                         SpiAccountConsent accountConsent, @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        byte[] initialAspspConsentData = aspspConsentDataProvider.loadAspspConsentData();
        if (ArrayUtils.isEmpty(initialAspspConsentData)) {
            return firstCallInstantiatingConsent(accountConsent, aspspConsentDataProvider, new SpiInitiateAisConsentResponse());
        }

        SCAConsentResponseTO aisConsentResponse;
        try {
            aisConsentResponse = initiateConsentInternal(accountConsent, initialAspspConsentData);
        } catch (FeignException feignException) {
            String devMessage = feignExceptionReader.getErrorMessage(feignException);
            logger.error("Initiate AIS consent failed: consent ID {}, devMessage {}", accountConsent.getId(), devMessage);
            return SpiResponse.<SpiInitiateAisConsentResponse>builder()
                           .error(FeignExceptionHandler.getFailureMessage(feignException, FORMAT_ERROR, "Addressed account is unknown to the ASPSP or not associated to the PSU."))
                           .build();
        }

        logger.info(SCA_STATUS_LOG, aisConsentResponse.getScaStatus());
        aspspConsentDataProvider.updateAspspConsentData(consentDataService.store(aisConsentResponse));

        return SpiResponse.<SpiInitiateAisConsentResponse>builder()
                       .payload(new SpiInitiateAisConsentResponse(accountConsent.getAccess(), false, ""))
                       .build();
    }

    /*
     * Maybe store the corresponding token in the list of revoked token.
     *
     * TODO: Implement this functionality
     *
     */
    @Override
    public SpiResponse<VoidResponse> revokeAisConsent(@NotNull SpiContextData contextData,
                                                      SpiAccountConsent accountConsent, @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        try {
            SCAConsentResponseTO sca = consentDataService.response(aspspConsentDataProvider.loadAspspConsentData(), SCAConsentResponseTO.class, false);
            sca.setScaStatus(FINALISED);
            sca.setStatusDate(LocalDateTime.now());
            sca.setBearerToken(new BearerTokenTO());// remove existing token.

            String scaStatusName = sca.getScaStatus().name();
            logger.info(SCA_STATUS_LOG, scaStatusName);

            aspspConsentDataProvider.updateAspspConsentData(consentDataService.store(sca));
        } catch (FeignException feignException) {
            String devMessage = feignExceptionReader.getErrorMessage(feignException);
            logger.error("Revoke AIS consent failed: consent ID {}, devMessage {}", accountConsent.getId(), devMessage);
            return SpiResponse.<VoidResponse>builder()
                           .error(FeignExceptionHandler.getFailureMessage(feignException, PSU_CREDENTIALS_INVALID, feignException.getMessage()))
                           .build();
        }

        return SpiResponse.<SpiResponse.VoidResponse>builder()
                       .payload(SpiResponse.voidResponse())
                       .build();
    }

    /*
     * Verify tan, store resulting token in the returned accountConsent. The token
     * must be presented when TPP requests account information.
     *
     */
    @Override
    public @NotNull SpiResponse<SpiVerifyScaAuthorisationResponse> verifyScaAuthorisation(@NotNull SpiContextData contextData,
                                                                                          @NotNull SpiScaConfirmation spiScaConfirmation,
                                                                                          @NotNull SpiAccountConsent accountConsent,
                                                                                          @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        try {
            SCAConsentResponseTO sca = consentDataService.response(aspspConsentDataProvider.loadAspspConsentData(), SCAConsentResponseTO.class);
            authRequestInterceptor.setAccessToken(sca.getBearerToken().getAccess_token());

            ResponseEntity<SCAConsentResponseTO> authorizeConsentResponse = consentRestClient
                                                                                    .authorizeConsent(sca.getConsentId(), sca.getAuthorisationId(), spiScaConfirmation.getTanNumber());
            SCAConsentResponseTO consentResponse = authorizeConsentResponse.getBody();

            String scaStatusName = sca.getScaStatus().name();
            logger.info(SCA_STATUS_LOG, scaStatusName);
            aspspConsentDataProvider.updateAspspConsentData(consentDataService.store(consentResponse, !consentResponse.isPartiallyAuthorised()));

            // TODO use real sca status from Ledgers for resolving consent status https://git.adorsys.de/adorsys/xs2a/ledgers/issues/206
            return SpiResponse.<SpiVerifyScaAuthorisationResponse>builder()
                           .payload(new SpiVerifyScaAuthorisationResponse(getConsentStatus(consentResponse)))
                           .build();
        } catch (FeignException feignException) {
            String devMessage = feignExceptionReader.getErrorMessage(feignException);
            logger.error("Verify sca authorisation failed: consent ID {}, devMessage {}", accountConsent.getId(), devMessage);
            return SpiResponse.<SpiVerifyScaAuthorisationResponse>builder()
                           .error(FeignExceptionHandler.getFailureMessage(feignException, PSU_CREDENTIALS_INVALID, devMessage, "authorisation PSU for consent was failed"))
                           .build();
        } finally {
            authRequestInterceptor.setAccessToken(null);
        }
    }

    /*
     * Login the user. On successful login, store the token in the resulting status
     * object.
     */
    @Override
    public SpiResponse<SpiPsuAuthorisationResponse> authorisePsu(@NotNull SpiContextData contextData,
                                                                 @NotNull SpiPsuData psuLoginData, String password, SpiAccountConsent aisConsent,
                                                                 @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        SCAConsentResponseTO originalResponse;
        try {
            originalResponse = consentDataService.response(aspspConsentDataProvider.loadAspspConsentData(), SCAConsentResponseTO.class, false);
        } catch (FeignException feignException) {
            String devMessage = feignExceptionReader.getErrorMessage(feignException);
            logger.error("Read aspspConsentData in authorise PSU failed: consent ID {}, devMessage {}", aisConsent.getId(), devMessage);
            return SpiResponse.<SpiPsuAuthorisationResponse>builder()
                           .error(new TppMessage(TOKEN_UNKNOWN, "Missing credentials. Expecting a bearer token in the consent data object."))
                           .build();
        }

        SpiResponse<SpiPsuAuthorisationResponse> authorisePsu = authorisationService.authorisePsuForConsent(
                psuLoginData, password, aisConsent.getId(), originalResponse, OpTypeTO.CONSENT, aspspConsentDataProvider);

        if (!authorisePsu.isSuccessful()) {
            String spiErrorMessage = authorisePsu.getErrors().get(0).getMessageText();
            return SpiResponse.<SpiPsuAuthorisationResponse>builder()
                           .error(new TppMessage(PSU_CREDENTIALS_INVALID, StringUtils.defaultIfBlank(spiErrorMessage, "authorisation PSU for consent was failed")))
                           .build();
        }

        SCAConsentResponseTO scaConsentResponse;

        try {
            scaConsentResponse = mapToScaConsentResponse(aisConsent, aspspConsentDataProvider.loadAspspConsentData());
        } catch (IOException e) {
            return SpiResponse.<SpiPsuAuthorisationResponse>builder()
                           .error(new TppMessage(FORMAT_ERROR, "Unknown response type"))
                           .build();
        }

        if (EnumSet.of(EXEMPTED, PSUAUTHENTICATED, PSUIDENTIFIED).contains(scaConsentResponse.getScaStatus())) {

            SCAConsentResponseTO aisConsentResponse;

            try {
                aisConsentResponse = initiateConsentInternal(aisConsent, aspspConsentDataProvider.loadAspspConsentData());
            } catch (FeignException feignException) {
                String devMessage = feignExceptionReader.getErrorMessage(feignException);
                logger.error("Initiate consent internal in authorise PSU failed: consent ID {}, devMessage {}", aisConsent.getId(), devMessage);
                return SpiResponse.<SpiPsuAuthorisationResponse>builder()
                               .error(FeignExceptionHandler.getFailureMessage(feignException, FORMAT_ERROR, "Addressed account is unknown to the ASPSP or not associated to the PSU."))
                               .build();
            }

            aspspConsentDataProvider.updateAspspConsentData(consentDataService.store(aisConsentResponse));

            return SpiResponse.<SpiPsuAuthorisationResponse>builder()
                           .payload(authorisePsu.getPayload())
                           .build();
        }// DO not change. AuthorizePsu is mutable. //TODO @fpo fix this

        return SpiResponse.<SpiPsuAuthorisationResponse>builder()
                       .payload(authorisePsu.getPayload())
                       .build();
    }

    /**
     * This call must follow an init consent request, therefore we are expecting the
     * {@link AspspConsentData} object to contain a {@link SCAConsentResponseTO}
     * response.
     */
    @Override
    public SpiResponse<List<SpiAuthenticationObject>> requestAvailableScaMethods(@NotNull SpiContextData contextData,
                                                                                 SpiAccountConsent businessObject, @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        try {
            SCAConsentResponseTO sca = consentDataService.response(aspspConsentDataProvider.loadAspspConsentData(), SCAConsentResponseTO.class);
            if (sca.getScaMethods() != null) {

                // Validate the access token
                BearerTokenTO bearerTokenTO = authorisationService.validateToken(sca.getBearerToken().getAccess_token());
                sca.setBearerToken(bearerTokenTO);

                // Return contained sca methods.
                List<ScaUserDataTO> scaMethods = sca.getScaMethods();
                List<SpiAuthenticationObject> authenticationObjects = scaMethodConverter.toSpiAuthenticationObjectList(scaMethods);
                aspspConsentDataProvider.updateAspspConsentData(consentDataService.store(sca));
                return SpiResponse.<List<SpiAuthenticationObject>>builder()
                               .payload(authenticationObjects)
                               .build();
            } else {
                logger.error("Process mismatch. Current SCA Status is {}", sca.getScaStatus());
                return SpiResponse.<List<SpiAuthenticationObject>>builder()
                               .error(new TppMessage(SCA_METHOD_UNKNOWN, "Process mismatch. PSU does not have any SCA method"))
                               .build();
            }
        } catch (FeignException feignException) {
            String devMessage = feignExceptionReader.getErrorMessage(feignException);
            logger.error("Read available sca methods failed: consent ID {}, devMessage {}", businessObject.getId(), devMessage);
            return SpiResponse.<List<SpiAuthenticationObject>>builder()
                           .error(new TppMessage(FORMAT_ERROR, "Getting SCA methods failed"))
                           .build();
        }
    }

    @Override
    public @NotNull SpiResponse<SpiAuthorizationCodeResult> requestAuthorisationCode(@NotNull SpiContextData contextData, @NotNull String authenticationMethodId, @NotNull SpiAccountConsent businessObject, @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        SCAConsentResponseTO sca = consentDataService.response(aspspConsentDataProvider.loadAspspConsentData(), SCAConsentResponseTO.class);
        if (EnumSet.of(PSUIDENTIFIED, PSUAUTHENTICATED).contains(sca.getScaStatus())) {
            try {
                authRequestInterceptor.setAccessToken(sca.getBearerToken().getAccess_token());
                logger.info("Request to generate SCA {}", sca.getConsentId());
                ResponseEntity<SCAConsentResponseTO> selectMethodResponse = consentRestClient.selectMethod(sca.getConsentId(), sca.getAuthorisationId(), authenticationMethodId);
                logger.info("SCA was send, operationId is {}", sca.getConsentId());
                SCAConsentResponseTO authCodeResponse = selectMethodResponse.getBody();
                if (authCodeResponse != null && authCodeResponse.getBearerToken() == null) {
                    // TODO: hack. Core banking is supposed to always return a token. @fpo
                    authCodeResponse.setBearerToken(sca.getBearerToken());
                }
                return authorisationService.returnScaMethodSelection(aspspConsentDataProvider, authCodeResponse);
            } catch (FeignException feignException) {
                String devMessage = feignExceptionReader.getErrorMessage(feignException);
                logger.error("Request authorisation code failed: consent ID {}, devMessage {}", businessObject.getId(), devMessage);
                TppMessage errorMessage = new TppMessage(getMessageErrorCodeByStatus(feignException.status()), StringUtils.defaultIfBlank(devMessage, "No message from Bank available."));
                return SpiResponse.<SpiAuthorizationCodeResult>builder()
                               // TODO fix response form ledgers https://git.adorsys.de/adorsys/xs2a/psd2-dynamic-sandbox/issues/185
                               .error(errorMessage)
                               .build();
            } finally {
                authRequestInterceptor.setAccessToken(null);
            }
        } else {
            return authorisationService.getResponseIfScaSelected(aspspConsentDataProvider, sca);
        }
    }

    @Override
    public @NotNull SpiResponse<SpiAuthorisationDecoupledScaResponse> startScaDecoupled(@NotNull SpiContextData contextData, @NotNull String authorisationId, @Nullable String authenticationMethodId, @NotNull SpiAccountConsent businessObject, @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider) {
        if (authenticationMethodId == null) {
            return SpiResponse.<SpiAuthorisationDecoupledScaResponse>builder()
                           .error(new TppMessage(SERVICE_NOT_SUPPORTED, DECOUPLED_NOT_SUPPORTED_MESSAGE))
                           .build();
        }

        SpiResponse<SpiAuthorizationCodeResult> response = requestAuthorisationCode(contextData, authenticationMethodId, businessObject, aspspConsentDataProvider);

        String psuMessage = generatePsuMessage(contextData, authorisationId, aspspConsentDataProvider, response);
        return response.hasError()
                       ? SpiResponse.<SpiAuthorisationDecoupledScaResponse>builder().error(response.getErrors()).build()
                       : SpiResponse.<SpiAuthorisationDecoupledScaResponse>builder().payload(new SpiAuthorisationDecoupledScaResponse(psuMessage)).build();
    }

    ConsentStatus getConsentStatus(SCAConsentResponseTO consentResponse) {
        if (consentResponse != null
                    && consentResponse.isMultilevelScaRequired()
                    && consentResponse.isPartiallyAuthorised()
                    && FINALISED.equals(consentResponse.getScaStatus())) {
            return ConsentStatus.PARTIALLY_AUTHORISED;
        }
        return ConsentStatus.VALID;
    }

    private String generatePsuMessage(@NotNull SpiContextData contextData, @NotNull String authorisationId, @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider, SpiResponse<SpiAuthorizationCodeResult> response) {
        List<String> challengeDataParts = Arrays.asList(response.getPayload().getChallengeData().getAdditionalInformation().split(" "));
        int indexOfTan = challengeDataParts.indexOf("is") + 1;
        String encryptedConsentId = "";
        try {
            encryptedConsentId = (String) FieldUtils.readField(aspspConsentDataProvider, "encryptedConsentId", true);
        } catch (IllegalAccessException e) {
            logger.error("could not read encrypted consent id");
        }
        String url = onlineBankingUrl.replace(USER_LOGIN, contextData.getPsuData().getPsuId())
                             .replace(CONSENT_ID, encryptedConsentId)
                             .replace(AUTH_ID, authorisationId)
                             .replace(TAN, challengeDataParts.get(indexOfTan));

        return format(DECOUPLED_USR_MSG, url);
    }

    private <T extends SpiInitiateAisConsentResponse> SpiResponse<T> firstCallInstantiatingConsent(
            @NotNull SpiAccountConsent accountConsent,
            @NotNull SpiAspspConsentDataProvider aspspConsentDataProvider, T responsePayload) {
        SCAConsentResponseTO response = new SCAConsentResponseTO();
        response.setScaStatus(STARTED);
        responsePayload.setAccountAccess(accountConsent.getAccess());
        aspspConsentDataProvider.updateAspspConsentData(consentDataService.store(response, false));
        return SpiResponse.<T>builder()
                       .payload(responsePayload)
                       .build();
    }

    private SCAConsentResponseTO mapToScaConsentResponse(SpiAccountConsent businessObject, byte[] aspspConsentData) throws IOException {
        SCALoginResponseTO scaResponseTO = tokenStorageService.fromBytes(aspspConsentData, SCALoginResponseTO.class);
        SCAConsentResponseTO consentResponse = scaLoginMapper.toConsentResponse(scaResponseTO);
        consentResponse.setObjectType(SCAConsentResponseTO.class.getSimpleName());
        consentResponse.setConsentId(businessObject.getId());
        return consentResponse;
    }

    private SCAConsentResponseTO initiateConsentInternal(SpiAccountConsent accountConsent, byte[] initialAspspConsentData) throws FeignException {
        try {
            SCAResponseTO sca = consentDataService.response(initialAspspConsentData);
            authRequestInterceptor.setAccessToken(sca.getBearerToken().getAccess_token());

            AisConsentTO aisConsent = aisConsentMapper.mapToAisConsent(accountConsent);

            // Bearer token only returned in case of exempted consent.
            ResponseEntity<SCAConsentResponseTO> consentResponse = consentRestClient.startSCA(accountConsent.getId(),
                                                                                              aisConsent);
            SCAConsentResponseTO response = consentResponse.getBody();

            if (response != null && response.getBearerToken() == null) {
                response.setBearerToken(sca.getBearerToken());
            }
            return response;
        } finally {
            authRequestInterceptor.setAccessToken(null);
        }
    }

    private MessageErrorCode getMessageErrorCodeByStatus(int status) {
        MessageErrorCode errorCode = INTERNAL_SERVER_ERROR;
        if (status == 501) {
            errorCode = SCA_METHOD_UNKNOWN;
        }
        if (Arrays.asList(400, 401, 403).contains(status)) {
            errorCode = FORMAT_ERROR;
        }
        if (status == 404) {
            errorCode = PSU_CREDENTIALS_INVALID;
        }
        return errorCode;
    }
}
