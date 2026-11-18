package io.supportflow.customer.dto.customer;

/** 改档案入参（null = 不改这一项） */
public record ProfileBody(String name, String phone, Integer level, Integer customerType,
                          Integer riskLevel, String remark) {
}
