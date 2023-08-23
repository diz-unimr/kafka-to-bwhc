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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@EnableKafka
@Configuration
public class MTBFileKafkaConsumer{
    private static final Logger log = LoggerFactory.getLogger(MTBFileKafkaConsumer.class);
    private final MTBFileToBwhcSenderClient mtbFileToBwhcSenderClient;
    private final KafkaTemplate<String, BwhcResponseKafka> kafkaTemplate;

    @Value("${spring.kafka.producer.topic}")
    private final String dnpmResponseTopic = "dnpm-response";

    @Autowired
    public MTBFileKafkaConsumer(MTBFileToBwhcSenderClient mtbFileToBwhcSenderClient,
                                KafkaTemplate<String, BwhcResponseKafka> kafkaTemplate) {
        this.mtbFileToBwhcSenderClient = mtbFileToBwhcSenderClient;
        this.kafkaTemplate = kafkaTemplate;
        kafkaTemplate.setDefaultTopic(dnpmResponseTopic);
    }

    @KafkaListener(topics = "${spring.kafka.consumer.topic}")
    public void listen(ConsumerRecord<String, String> record) throws JacksonException {
        String message = record.value();
        System.out.println(message);
        String key = record.key();
        BwhcResponseKafka eachResponseBeforeKafka = mtbFileToBwhcSenderClient.sendRequestToBwhc(message);
        if (eachResponseBeforeKafka.getStatusCode() != 900){
            kafkaTemplate.sendDefault(key,eachResponseBeforeKafka);
            // writing the response in a kafka topic
            log.debug("Response successfully written in kafka");
        }else {
            // write a response in response-topic and throw a runtime exception
            kafkaTemplate.sendDefault(key,eachResponseBeforeKafka);
            log.debug("Response successfully written in kafka");
            throw new RuntimeException();
        }
    }
}
