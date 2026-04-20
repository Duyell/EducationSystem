package duyell.service.impl;

import duyell.mapper.SysUserMapper;
import duyell.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * @author duyell
 */
@Service
public class HomeServiceImpl implements HomeService {
    private final SysUserMapper sysUserMapper;

    public HomeServiceImpl(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> map = new HashMap<>(2);
        map.put("totalStudents", sysUserMapper.countStudent());
        map.put("totalTeachers", sysUserMapper.countTeacher());
        map.put("totalCourses", sysUserMapper.countCourse());
        return map;
    }
}
