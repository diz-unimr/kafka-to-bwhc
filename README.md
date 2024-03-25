# kafka-to-bwhc
This project is a part of DNPM (Deutsches Netzwerk für Personalisierte Medizin) specially designed to fulfill the
requirement of the DIZ Marburg IT infrastructure.

## Aim of the project

- To consume the records (MTB-file JSON) from a kafka topic
- Send the MTB-File to the bwhc-Node of DIZ-Marburg via REST API
- Collect each HTTP response from bwhc-Node and send it to a kafka topic

## Required parameter
- DNPM_TOPIC: Topic with MTB-File and consumed by consumer
- DNPM_RESPONSE_TOPIC: Topic where the HTTP response from bwhc will be produced by producer 
- URL_BWHC_POST: The REST endpoint of bwhc-node for POST request
- URL_BWHC_DELETE: The REST endpoint of bwhc-node for 

## TODO
