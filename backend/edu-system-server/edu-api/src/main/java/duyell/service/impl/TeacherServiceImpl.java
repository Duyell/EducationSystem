package duyell.service.impl;

import com.duyell.SysUser;
import com.duyell.Teacher;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.SysUserMapper;
import duyell.mapper.TeacherMapper;
import duyell.service.TeacherService;
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
public class TeacherServiceImpl implements TeacherService {
    private final TeacherMapper teacherMapper;
    private final SysUserMapper sysUserMapper;
    @Override
    public PageResult<Teacher> page(Integer pageNum, Integer pageSize, String teacherName, String teacherId, Integer collegeId, String title) {
        //1.设置分页参数
        Page<Teacher> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询
        List<Teacher> list = teacherMapper.list(teacherName,teacherId,collegeId,title);
        return new PageResult<>(pageResult.getTotal(), list);
    }

    @Override
    public void add(Teacher teacher) {
        SysUser sysUser = new SysUser();
        sysUser.setUsername(teacher.getTeacherId());
        sysUser.setPassword("123456");
        sysUser.setRole("teacher");
        sysUser.setEmail(teacher.getEmail());
        sysUser.setPhone(teacher.getPhone());
        sysUserMapper.add(sysUser);
        teacher.setUserId(sysUser.getId());
        teacherMapper.add(teacher);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(Integer teacherId) {
        sysUserMapper.deleteByUsernames(List.of(teacherMapper.selectTeacherById(teacherId)));
        teacherMapper.deleteTeacherByIds(List.of(teacherId));
    }

    @Override
    public void update(Teacher teacher) {
        teacherMapper.updateTeacher(teacher);
    }
}
