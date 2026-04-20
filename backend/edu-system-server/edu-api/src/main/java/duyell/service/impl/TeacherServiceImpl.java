package duyell.service.impl;

import com.duyell.Teacher;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.TeacherMapper;
import duyell.service.TeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

@RequiredArgsConstructor
@Service
public class TeacherServiceImpl implements TeacherService {
    private final TeacherMapper teacherMapper;

    @Override
    public PageResult<Teacher> page(Integer pageNum, Integer pageSize) {
        //1.设置分页参数
        Page<Teacher> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询
        List<Teacher> list = teacherMapper.list();
        return new PageResult<>(pageResult.getTotal(), list);
    }

    @Override
    public void add(Teacher teacher) {
        teacherMapper.add(teacher);
    }

    @Override
    public void delete(Integer teacherId) {
        teacherMapper.deleteTeacherByIds(List.of(teacherId));
    }

    @Override
    public void update(Teacher teacher) {
        teacherMapper.updateTeacher(teacher);
    }
}
