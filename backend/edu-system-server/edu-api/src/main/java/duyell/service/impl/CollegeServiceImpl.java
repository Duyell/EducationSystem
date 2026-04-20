package duyell.service.impl;

import com.duyell.College;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.CollegeMapper;
import duyell.service.CollegeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class CollegeServiceImpl implements CollegeService {
    private final CollegeMapper collegeMapper;

    @Override
    public PageResult<College> page(Integer pageNum, Integer pageSize) {
        //1.设置分页参数
        Page<College> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询数据
        List<College> collegeList = collegeMapper.list();
        return new PageResult<>(pageResult.getTotal(), collegeList);
    }

    @Override
    public void add(College college) {
        collegeMapper.addCollege(college.getCollegeName());
    }

    @Override
    public void delete(Integer collegeId) {
        collegeMapper.deleteCollege(collegeId);
    }

    @Override
    public void update(College college) {
        collegeMapper.updateCollege(college.getId(), college.getCollegeName());
    }
}
