package duyell.controller;

import duyell.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author duyell
 */
@RestController
@RequestMapping("/home")
public class HomeController {
    private final HomeService homeService;
    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }
    @GetMapping("/statistics")
    public Map<String, Object> statistics() {
        // 直接返回数据，前端直接解析
        return homeService.getStatistics();
    }
}
