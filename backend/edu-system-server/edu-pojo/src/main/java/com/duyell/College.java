package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author duyell
 * 学院类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class College {
    private Integer id;
    private String collegeName;
}
