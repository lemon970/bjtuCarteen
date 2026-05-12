package com.bjtu.simulation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {

    @GetMapping({"/frontend", "/frontend/"})
    public String frontendEntry() {
        return "forward:/frontend/index.html";
    }
}
