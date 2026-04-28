package com.paycore.transactionservice.client;

import com.paycore.transactionservice.dto.client.CreateLedgerEntryClientRequest;
import com.paycore.transactionservice.dto.client.CreateLedgerEntryClientResponse;
import com.paycore.transactionservice.dto.client.ReverseLedgerEntryClientRequest;
import com.paycore.transactionservice.dto.client.ReverseLedgerEntryClientResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "ledger-service", url = "${transaction.services.ledger-service-url:http://ledger-service}")
public interface LedgerServiceClient {

    @PostMapping("/internal/v1/ledger/entries")
    ResponseEntity<CreateLedgerEntryClientResponse> processDoubleEntry(@RequestBody CreateLedgerEntryClientRequest request);

    @PostMapping("/internal/v1/ledger/entries/reversal")
    ResponseEntity<ReverseLedgerEntryClientResponse> processReversal(@RequestBody ReverseLedgerEntryClientRequest request);
}
