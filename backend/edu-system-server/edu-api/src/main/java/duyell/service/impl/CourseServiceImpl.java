package duyell.service.impl;

import com.duyell.Course;
import com.duyell.CourseSelection;
import com.duyell.Student;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.mapper.StudentMapper;
import duyell.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class CourseServiceImpl implements CourseService {
    private final CourseMapper courseMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final StudentMapper studentMapper;

    @Override
    public PageResult<Course> page(Integer pageNum, Integer pageSize, String courseName,String teacherName, String teacherId, Integer collegeId, Integer credit, Integer classHour, Integer maxStudent) {
        Page<Course> pageResult=  PageHelper.startPage(pageNum, pageSize);
        List<Course> courseList = courseMapper.list( courseName, teacherName, teacherId, collegeId, credit, classHour, maxStudent);
        return new PageResult<>(pageResult.getTotal(), courseList);
    }

    @Override
    public void add(Course course) {
        courseMapper.add(course);
    }

    @Override
    public void delete(Integer courseId) {
        courseMapper.delete(courseId);
    }

    @Override
    public void update(Course course) {
        courseMapper.update( course);
    }

    @Override
    public List<Course> listByTeacherId(String teacherId) {
        return courseMapper.selectByTeacherId(teacherId);
    }

    @Override
    public List<Student> getStudentsByCourseId(Integer courseId) {
        List<CourseSelection> selections = courseSelectionMapper.selectByCourseId(courseId);
        List<String> studentIds = selections.stream()
                .map(CourseSelection::getStudentId)
                .collect(Collectors.toList());
        return studentMapper.selectByStudentIds(studentIds);
    }
}
