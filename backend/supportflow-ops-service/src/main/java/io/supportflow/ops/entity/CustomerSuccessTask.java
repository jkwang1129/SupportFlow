package io.supportflow.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * customer_success_task 客户成功回访任务。

 * <p>运营一条条建：给哪个租户、什么类型（续费/健康/扩展/其它）、谁负责、什么时候回访；
 * 回访完写上结论并置为已完成。逾期未完成的任务在页面上标红——客户成功最怕的就是"忘了跟"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer_success_task")
public class CustomerSuccessTask {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 企业名快照（租户改名也能看懂历史任务） */
    private String companyName;

    /** 1-续费、2-健康、3-扩展、4-其它 */
    private Short taskType;

    private String title;

    private Long ownerId;

    private String ownerName;

    private LocalDateTime dueTime;

    /** 1-待回访、2-已完成、3-已取消 */
    private Short status;

    private String result;

    private LocalDateTime finishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
