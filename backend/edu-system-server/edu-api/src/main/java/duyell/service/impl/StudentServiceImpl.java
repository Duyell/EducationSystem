package duyell.service.impl;

import com.duyell.Student;
import com.duyell.SysUser;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.StudentMapper;
import duyell.mapper.SysUserMapper;
import duyell.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.JwtUtil;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class StudentServiceImpl implements StudentService {
    private final StudentMapper studentMapper;
    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;

    @Override
    public PageResult<Student> page(Integer pageNum, Integer pageSize, String studentName, String studentId, Integer collegeId, Integer majorId, Integer clazzId) {
        //1.设置分页参数
        Page<Student> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询数据
        List<Student> studentList = studentMapper.list(studentName, studentId, collegeId, majorId, clazzId);
        return new PageResult<>(pageResult.getTotal(), studentList);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void add(Student student) {
        SysUser sysUser = new SysUser();
        sysUser.setUsername(student.getStudentId());
        String password = "123456";
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String encodePassword = passwordEncoder.encode(password);
        sysUser.setPassword(encodePassword);
        sysUser.setRole("student");
        sysUser.setEmail(student.getEmail());
        sysUser.setPhone(student.getPhone());
        sysUserMapper.add(sysUser);
        student.setUserId(sysUser.getId());
        studentMapper.add(student);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(Integer studentId) {
        sysUserMapper.deleteByUsernames(List.of(studentMapper.selectStudentById(studentId)));
        studentMapper.deleteStudentByIds(List.of(studentId));
    }

    @Override
    public void update(Student student) {
        studentMapper.updateStudent(student);
    }
}
