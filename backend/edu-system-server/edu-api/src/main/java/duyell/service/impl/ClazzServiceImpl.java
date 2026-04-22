package duyell.service.impl;

import com.duyell.Clazz;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.ClazzMapper;
import duyell.service.ClazzService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class ClazzServiceImpl implements ClazzService {
    private final ClazzMapper clazzMapper;
    @Override
    public PageResult<Clazz> page(Integer pageNum, Integer pageSize, String clazzName, String grade, Integer majorId, Integer collegeId) {
        //1.设置分页参数
        Page<Clazz> pageResult=  PageHelper.startPage(pageNum, pageSize);
        //2.查询数据
        List<Clazz> clazzList = clazzMapper.list(clazzName, grade, majorId, collegeId);
        return new PageResult<>(pageResult.getTotal(), clazzList);
    }

    @Override
    public void add(Clazz clazz) {
        clazzMapper.add(clazz);
    }

    @Override
    public void delete(Integer clazzId) {
        clazzMapper.delete(clazzId);
    }

    @Override
    public void update(Clazz clazz) {
        clazzMapper.update(clazz);
    }
}
