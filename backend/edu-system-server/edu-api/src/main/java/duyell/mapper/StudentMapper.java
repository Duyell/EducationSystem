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
    @Select("select * from student")
    List<Student> list();

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
     * 根据用户id查找学生名称
     * @param id 用户id
     * @return 学生对象
     */
    @Select("select * from student where id = #{id}")
    Student selectStudentById(Integer id);

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
    void deleteStudentByIds(List<String> ids);

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
}
