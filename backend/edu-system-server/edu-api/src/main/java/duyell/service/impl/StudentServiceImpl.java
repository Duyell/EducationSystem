package duyell.service.impl;

import com.duyell.Student;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.StudentMapper;
import duyell.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class StudentServiceImpl implements StudentService {
    private final StudentMapper studentMapper;

    @Override
    public PageResult<Student> page(Integer pageNum, Integer pageSize) {
        //1.设置分页参数
        Page<Student> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询数据
        List<Student> studentList = studentMapper.list();
        return new PageResult<>(pageResult.getTotal(), studentList);
    }

    @Override
    public void add(Student student) {
        studentMapper.add( student);
    }

    @Override
    public void delete(Integer studentId) {
        studentMapper.deleteStudentByIds(List.of(studentId));
    }

    @Override
    public void update(Student student) {
        studentMapper.updateStudent(student);
    }
}
