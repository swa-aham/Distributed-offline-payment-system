package com.offlineupi.edge.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class EdgeConfig {

    @Value("${edge.node.id:edge-node-1}")
    private String id;

    @Value("${edge.node.owner.vpa:alice@upi}")
    private String ownerVpa;
}
