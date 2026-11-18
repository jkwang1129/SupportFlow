package io.supportflow.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-service：认证、用户、角色权限、个人中心（user_db）。
 */
@SpringBootApplication(scanBasePackages = "io.supportflow")
@MapperScan("io.supportflow.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
