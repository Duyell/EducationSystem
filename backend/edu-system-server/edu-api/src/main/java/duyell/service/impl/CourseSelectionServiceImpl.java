package duyell.service.impl;

import com.duyell.Course;
import com.duyell.CourseSelection;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.service.CourseSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author duyell
 * 选课业务实现
 */
@RequiredArgsConstructor
@Service
public class CourseSelectionServiceImpl implements CourseSelectionService {

    private final CourseSelectionMapper courseSelectionMapper;
    private final CourseMapper courseMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void select(Integer courseId, String studentId) {
        // 1. 行锁锁定课程记录：同一课程的选课操作串行执行，防止并发超卖
        Course course = courseMapper.selectCourseByIdForUpdate(courseId);
        if (course == null) {
            throw new BusinessException("课程不存在");
        }
        // 2. 重复选课校验
        if (courseSelectionMapper.select(courseId, studentId) != null) {
            throw new BusinessException("该课程已选过，请勿重复选课");
        }
        // 3. 容量校验
        if (course.getMaxStudent() != null && courseSelectionMapper.countByCourseId(courseId) >= course.getMaxStudent()) {
            throw new BusinessException("该课程名额已满");
        }
        // 4. 插入（数据库唯一约束兜底，异常时给出友好提示）
        try {
            courseSelectionMapper.add(courseId, studentId);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该课程已选过，请勿重复选课");
        }
    }

    @Override
    public void drop(Integer courseId, String studentId) {
        courseSelectionMapper.delete(courseId, studentId);
    }

    @Override
    public List<Course> listMyCourses(String studentId) {
        List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(studentId);
        if (selections.isEmpty()) {
            return List.of();
        }
        // IN 查询一次取出全部课程，避免 N+1
        List<Integer> courseIds = selections.stream()
                .map(CourseSelection::getCourseId)
                .collect(Collectors.toList());
        return courseMapper.selectByIds(courseIds);
    }

    @Override
    public List<Integer> listMyCourseIds(String studentId) {
        return courseSelectionMapper.selectByStudentId(studentId).stream()
                .map(CourseSelection::getCourseId)
                .collect(Collectors.toList());
    }
}
