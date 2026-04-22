package duyell.service.impl;

import com.duyell.Major;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.MajorMapper;
import duyell.service.MajorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class MajorServiceImpl implements MajorService {
    private final MajorMapper majorMapper;
    @Override
    public PageResult<Major> page(Integer pageNum, Integer pageSize, Integer collegeId, String majorName) {
        Page<Major> pageResult=  PageHelper.startPage(pageNum, pageSize);
        List<Major> majorList = majorMapper.list(majorName,collegeId);
        return new PageResult<>(pageResult.getTotal(), majorList);
    }

    @Override
    public void add(Major major) {
        majorMapper.add(major);
    }

    @Override
    public void delete(Integer majorId) {
        majorMapper.delete(majorId);
    }

    @Override
    public void update(Major major) {
        majorMapper.update(major);
    }
}
