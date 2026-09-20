package duyell.mapper;

import com.duyell.Student;
import org.apache.ibatis.annotations.*;

import java.util.List;
/**
 * @author duyell
 */
@Mapper
public interface StudentMapper {
    /**
     * 分页查询
     * @return 查询列表
     */
    List<Student> list(@Param("studentName") String studentName,
                       @Param("studentId") String studentId,
                       @Param("collegeId") Integer collegeId,
                       @Param("majorId") Integer majorId,
                       @Param("clazzId") Integer clazzId);

    /**
     * 添加学生
     * @param student 学生对象
     */
    @Insert("insert into student(student_name,student_id,user_id,clazz_id,gender,birthday,phone,email) " +
            "values(#{studentName},#{studentId},#{userId},#{clazzId},#{gender},#{birthday},#{phone},#{email})")
    void add(Student student);

    /**
     * 根据学生id查找学生名称
     * @param studentId 学生id
     * @return 学生对象
     */
    @Select("select * from student where student_id = #{studentId}")
    Student selectStudentByStudentId(String studentId);

    /**
     * 根据用户id查找学生学号
     * @param id 用户id
     * @return 学生学号
     */
    @Select("select student_id from student where id = #{id}")
    String selectStudentById(Integer id);

    /**
     * 统计学生数量
     * @return 学生数量
     */
    @Select("select count(*) from student")
    int countStudent();

    /**
     * 根据用户id批量删除学生
     * @param ids 用户id的集合
     */
    void deleteStudentByIds(List<Integer> ids);

    /**
     * 根据学号批量删除学生
     * @param studentIds 学生学号的集合
     */
    void deleteStudentByStudentIds(List<String> studentIds);

    /**
     * 更新学生信息
     * @param student 学生
     */
    void updateStudent(Student student);

    /**
     * 根据学号列表查询学生
     * @param studentIds 学号列表
     * @return 学生列表
     */
    List<Student> selectByStudentIds(@Param("studentIds") List<String> studentIds);

    /**
     * 查询学生的「定位信息」：年级(clazz.grade) 与 专业(major_id)。
     *
     * <p>用于确定学生适用的培养计划版本（设计文档 §2.1 版本策略：老生沿用入学年级方案）。
     * 结果封装在 Student 的 grade / majorId 两个冗余字段里。
     *
     * @param studentId 学号
     * @return 含 grade / majorId / majorName / collegeName 的学生对象；学号不存在返回 null
     */
    Student selectWithGradeAndMajor(@Param("studentId") String studentId);

    /**
     * 查询同专业同年级的全部学号（用于专业内绩点排名）。
     *
     * <p>排名范围按「专业 + 年级」划分：不同年级课程不同，混排无意义。
     * 只取在读学生（status 非 WITHDRAWN）—— 休学可保留，退学不应参与排名。
     *
     * @param majorId 专业 id
     * @param grade   年级
     * @return 学号列表
     */
    @Select("""
            select s.student_id from student s
            join clazz c on s.clazz_id = c.id
            where c.major_id = #{majorId} and c.grade = #{grade}
              and (s.status is null or s.status <> 'WITHDRAWN')
            order by s.student_id
            """)
    List<String> listStudentIdsByMajorAndGrade(@Param("majorId") Integer majorId,
                                               @Param("grade") String grade);
}
