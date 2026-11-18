package io.supportflow.customer.dto.customer;

/** 客户列表查询条件 */
public record CustomerQuery(
        String keyword,
        Integer level,
        Integer riskLevel,
        /** 客户类型：1-个人、2-企业（不传=全部） */
        Integer customerType,
        Long tagId,
        String channel,
        Integer activeDays,
        Boolean hasTicket,
        String sort) {

    public static CustomerQuery empty() {
        return new CustomerQuery(null, null, null, null, null, null, null, null, null);
    }
}
