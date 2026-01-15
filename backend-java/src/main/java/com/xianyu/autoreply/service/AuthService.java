package com.xianyu.autoreply.service;

import com.xianyu.autoreply.entity.User;
import com.xianyu.autoreply.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;

    @Autowired
    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User login(String username, String password) {
        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isPresent() && Objects.equals(optUser.get().getPasswordHash(), password)) {
            return optUser.get();
        } else {
            return null;
        }
    }

    public User register(String username, String password, String email) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(password); // In real app, use BCrypt
        user.setEmail(email);
        user.setIsActive(true);
        return userRepository.save(user);
    }
}
