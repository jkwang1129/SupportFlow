package io.supportflow.customer.dto.customer;

/** 完整手机号（查看会留痕，所以返回的是"这次看到的那串号码"） */
public record PhoneVO(String phone, String masked) {
}
