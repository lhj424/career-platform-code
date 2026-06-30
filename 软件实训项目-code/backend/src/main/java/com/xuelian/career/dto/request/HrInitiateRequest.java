package com.xuelian.career.dto.request;

import lombok.Data;

/**
 * HR发起会话请求
 */
@Data
public class HrInitiateRequest {
    /** 候选人（学生）用户ID */
    private Long studentId;
    /** 岗位ID */
    private Long jobId;
    /** 岗位名称 */
    private String jobTitle;
}
