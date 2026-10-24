package com.rafay.user_service.controller.notifycontroller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.rafay.user_service.dto.NotifyDto;
import com.rafay.user_service.dto.notifydto.notifyFrontEndDto;
import com.rafay.user_service.service.notifyservice.NotifyService;

@RestController

public class UserNotify {
    @Autowired
    NotifyService notifyService;

    @PostMapping("/notify")
    public NotifyDto notifyUser(@RequestBody notifyFrontEndDto request) {
        String userId = request == null ? null : request.getUserId();

        System.out.println("User ID: " + userId); // Debugging line to print the userId
        System.out.println("Request Body: " + request); // Debugging line to print the request body
        return notifyService.getNotifyData(userId == null ? null : userId);
    }
}
