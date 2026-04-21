package duyell.mapper;

import com.duyell.Teacher;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author duyell
 */
@Mapper
public interface TeacherMapper {
    /**
     * 分页查询
     * @param teacherName 教师名称
     * @param teacherId  教师id
     * @param collegeId  学院id
     * @param title 职称
     * @return 查询列表
     */
    List<Teacher> list(@Param("teacherName") String teacherName, @Param("teacherId") String teacherId
            ,@Param("collegeId") Integer collegeId, @Param("title") String title);

    /**
     * 添加教师
     * @param teacher 教师对象
     */
    @Insert("insert into teacher(teacher_name,teacher_id,user_id,gender,birthday,phone,email,college_id,title) " +
            "values(#{teacherName},#{teacherId},#{userId},#{gender},#{birthday},#{phone},#{email},#{collegeId},#{title})")
    void add(Teacher teacher);

    /**
     * 根据教师id查找教师
     * @param teacherId 教师id
     * @return 教师对象
     */
    @Select("select * from teacher where teacher_id = #{teacherId}")
    Teacher selectTeacherByTeacherId(String teacherId);

    /**
     * 根据用户id查找教师名称
     * @param id 用户id
     * @return 教师名称
     */
    @Select("select teacher_id from teacher where id = #{id}")
    String selectTeacherById(Integer id);

    /**
     * 统计教师数量
     * @return 教师数量
     */
    @Select("select count(*) from teacher")
    int countTeacher();

    /**
     * 批量删除教师
     * @param ids 教师id的集合
     */
    void deleteTeacherByIds(List<Integer> ids);

    /**
     * 根据教师工号id批量删除教师
     * @param teacherIds 教师工号的集合
     */
    void deleteTeacherByTeacherIds(List<String> teacherIds);

    /**
     * 更新教师信息
     * @param teacher 教师
     */
    void updateTeacher(Teacher teacher);
}
