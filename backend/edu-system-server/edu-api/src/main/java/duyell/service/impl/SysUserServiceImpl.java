package duyell.service.impl;

import com.duyell.Student;
import com.duyell.SysUser;
import com.duyell.Teacher;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.StudentMapper;
import duyell.mapper.SysUserMapper;
import duyell.mapper.TeacherMapper;
import duyell.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class SysUserServiceImpl implements SysUserService {
    private final SysUserMapper sysUserMapper;
    private final StudentMapper studentMapper;
    private final TeacherMapper teacherMapper;

    @Override
    public PageResult<SysUser> page(Integer pageNum, Integer pageSize) {
        //1.设置分页参数
        Page<SysUser> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询
        List<SysUser> list = sysUserMapper.list();

        return new PageResult<>(pageResult.getTotal(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(SysUser sysUser) {
    	sysUserMapper.add(sysUser);
        String stu = "student";
        String tea = "teacher";
        if(stu.equals(sysUser.getRole())){
            Student s = new Student();
            s.setStudentName(sysUser.getUsername());
            s.setUserId(sysUser.getId());
            studentMapper.add(s);
        } else if (tea.equals(sysUser.getRole())) {
            Teacher t = new Teacher();
            t.setTeacherName(sysUser.getUsername());
            t.setUserId(sysUser.getId());
            teacherMapper.add(t);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String username) {
        SysUser sysUser = sysUserMapper.selectByUsername(username);
        String stu = "student";
        String tea = "teacher";
        if(stu.equals(sysUser.getRole())){
            studentMapper.deleteStudentByIds(List.of(sysUser.getUsername()));
        }else if (tea.equals(sysUser.getRole())) {
            teacherMapper.deleteTeacherByIds(List.of(sysUser.getUsername()));
        }else{
            throw new RuntimeException("管理员账号不可删除");
        }
        sysUserMapper.deleteByIds(List.of(username));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysUser sysUser) {
        sysUserMapper.update(sysUser);
        String stu = "student";
        String tea = "teacher";
        if(stu.equals(sysUser.getRole())){
            Student s = studentMapper.selectStudentByStudentId(sysUser.getUsername());
            s.setEmail(sysUser.getEmail());
            s.setPhone(sysUser.getPhone());
            studentMapper.updateStudent(s);
        } else if (tea.equals(sysUser.getRole())) {
            Teacher t = teacherMapper.selectTeacherByTeacherId(sysUser.getUsername());
            t.setEmail(sysUser.getEmail());
            t.setPhone(sysUser.getPhone());
            teacherMapper.updateTeacher(t);
        }else{
            throw new RuntimeException("管理员账号不可修改");
        }
        sysUserMapper.update(sysUser);
    }
}
