/*
 This file is part of KAFKA-TO-BWHC.

 KAFKA-TO-BWHC - Read MTB-file from a Apache Kafka topic > send MTB-file via REST to DIZ Marburg  BWHC Node >
 produce the HTTP Response to a new Apache Kafka topic
 Copyright (C) 2023  Datenintegrationszentrum Philipps-Universität Marburg

 KAFKA-TO-BWHC is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 KAFKA-TO-BWHC is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>
 */

package de.unimarburg.diz.kafkatobwhc;

import com.fasterxml.jackson.core.JacksonException;
import de.unimarburg.diz.kafkatobwhc.model.BwhcResponseKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.retry.policy.SimpleRetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Objects;
import org.apache.http.client.utils.URIBuilder;
import java.net.URI;
import java.net.URISyntaxException;

@Component
public class MTBFileToBwhcSenderClient {
    private static final Logger log = LoggerFactory.getLogger(MTBFileToBwhcSenderClient.class);
    private final String postUrl;
    private final String deleteUrl;
    private final RetryTemplate retryTemplate = defaultTemplate();
    private final ObjectMapper objectMapper;
    static ResponseEntity<Object> responseEntity;
    @Autowired
    public MTBFileToBwhcSenderClient(@Value("${services.mtbSender.post_url}") String postUrl,
                                     @Value("${services.mtbSender.delete_url}") String deleteUrl){
        this.postUrl = postUrl;
        this.deleteUrl = deleteUrl;
        objectMapper = new ObjectMapper();
    }

    public BwhcResponseKafka sendRequestToBwhc(String message_body) throws JacksonException, URISyntaxException, MalformedURLException {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var jsonNode = objectMapper.readTree(message_body);
        String message = jsonNode.get("content").toString();
        System.out.println(message);
        String requestId = jsonNode.get("requestId").asText();
        HttpEntity<String> requestEntity = new HttpEntity<>(message, headers);
        String request_type = decidePostOrDelete(message);
        String patientId = retunPID(message);
        BwhcResponseKafka bwhcResponseKafka = new BwhcResponseKafka();
        switch (request_type){
            case("post"):
                try {
                    responseEntity = retryTemplate.execute(ctx -> restTemplate
                            .exchange(postUrl, HttpMethod.POST, requestEntity, Object.class));
                    if (responseEntity.getStatusCode().is2xxSuccessful()) {
                        log.debug("API request succeeded");
                        bwhcResponseKafka.setRequestId(requestId);
                        bwhcResponseKafka.setStatusCode(responseEntity.getStatusCode().value());
                        bwhcResponseKafka.setStatusBody(responseEntity.getBody());
                        return bwhcResponseKafka;
                    }
                } catch (HttpClientErrorException e){
                    log.debug("API request succeeded");
                    bwhcResponseKafka.setRequestId(requestId);
                    bwhcResponseKafka.setStatusCode(e.getStatusCode().value());
                    bwhcResponseKafka.setStatusBody(e.getResponseBodyAs(Object.class));
                    return bwhcResponseKafka;
                } catch (RestClientException e){
                    return createErrorResponseKafka(bwhcResponseKafka, requestId);
                }
            case("delete"):
                try {
                    URL givenUrl = new URL(deleteUrl);
                    String existingPath = givenUrl.getPath();
                    String newPath = existingPath + patientId;
                    URIBuilder uriBuilder = new URIBuilder(deleteUrl)
                                .setPath(newPath);
                    URI deleteUrlPid= uriBuilder.build();
                    log.debug("Delete URL:" + deleteUrlPid.toString());
                    responseEntity = retryTemplate.execute(ctx -> restTemplate
                            .exchange(deleteUrlPid, HttpMethod.DELETE, requestEntity, Object.class));
                    if (responseEntity.getStatusCode().is2xxSuccessful()) {
                        log.debug("Deletion in bwhc successful");
                        bwhcResponseKafka.setRequestId(requestId);
                        bwhcResponseKafka.setStatusCode(responseEntity.getStatusCode().value());
                        bwhcResponseKafka.setStatusBody("Patient is successfully deleted from BWHC.");
                        return bwhcResponseKafka;
                    }
                }catch (RestClientException e) {
                    return createErrorResponseKafka(bwhcResponseKafka, requestId);
                }
        }
        return bwhcResponseKafka;
    }

    private BwhcResponseKafka createErrorResponseKafka(BwhcResponseKafka bwhcResponseKafka, String requestId) {
        log.error("BWHC server is not reachable");
        bwhcResponseKafka.setRequestId(requestId);
        bwhcResponseKafka.setStatusCode(900);
        bwhcResponseKafka.setStatusBody("BWCH server is not available.");
        return bwhcResponseKafka;
    }

    public String decidePostOrDelete (String message) throws JacksonException {
        String request_type = "";
        try {
            var jsonNode = objectMapper.readTree(message);
            String consent_status = jsonNode.get("consent").get("status").asText();
            if (Objects.equals(consent_status,"active")){
                log.debug("Required to send a POST request");
                request_type = "post";
                log.debug("Required to send a POST request");
            } else if (Objects.equals(consent_status,"rejected")){
                log.debug("Required to send a DELETE request");
                request_type = "delete";
                log.debug("Required to send a DELETE request");
            }
        } catch (JacksonException jsonException){
            log.error("JSON parsing failed.", jsonException);
            throw jsonException;
        }
        return request_type;
    }


    public String retunPID (String message) throws JacksonException {
        String patientID = "";
        try {
            var jsonNode = objectMapper.readTree(message);
            patientID = jsonNode.get("consent").get("patient").asText();
        } catch (JacksonException jsonException){
            log.error("JSON parsing failed.", jsonException);
            throw jsonException;
        }
        return patientID;
    }

    public static RetryTemplate defaultTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(5000);
        backOffPolicy.setMultiplier(1.25);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        HashMap<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(RestClientException.class,true);
        RetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions);
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context,
                                                         RetryCallback<T, E> callback, Throwable throwable) {
                log.warn("HTTP Error occurred: {}. Retrying {}", throwable.getMessage(),
                        context.getRetryCount());
            }
        });
        return retryTemplate;
    }
}
