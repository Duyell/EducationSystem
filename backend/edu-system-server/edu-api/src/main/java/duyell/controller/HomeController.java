package duyell.controller;

import duyell.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utils.Result;

import java.util.Map;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/home")
public class HomeController {
    private final HomeService homeService;

    @GetMapping("/statistics")
    public Result<Map<String, Object>> statistics() {
        return Result.success(homeService.getStatistics());
    }
}
