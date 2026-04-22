package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author duyell
 * 专业类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Major {
    private Integer id;
    private String majorName;
    private Integer collegeId;

    private String collegeName;
}
