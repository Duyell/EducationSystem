package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 教室。
 *
 * <p>规模：8 栋 × 10 层 × 10 间 = 800 间（用户给出），由迁移脚本**程序化生成**，
 * 不手写 INSERT。容量取 40 + 楼层*20 = 60~240，便于「按容量最接近」推荐时有区分度。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(4)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    private Integer id;

    /** 楼栋，如 教1 */
    private String building;

    /** 楼层 1~10 */
    private Integer floorNo;

    /** 房间号 01~10 */
    private String roomNo;

    /** 展示名，如 教1-301 */
    private String roomName;

    /** 容量 */
    private Integer capacity;

    /** NORMAL / LAB / MULTIMEDIA */
    private String roomType;

    /** 1=可用 0=停用 */
    private Integer status;

    public static final String TYPE_NORMAL = "NORMAL";
    public static final String TYPE_LAB = "LAB";
    public static final String TYPE_MULTIMEDIA = "MULTIMEDIA";
}
