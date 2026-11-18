package io.supportflow.billing.dto;

/** 对公账户信息（存在 config_json 里） */
public record CorpAccount(String accountName, String accountNo, String bankName) {
}
