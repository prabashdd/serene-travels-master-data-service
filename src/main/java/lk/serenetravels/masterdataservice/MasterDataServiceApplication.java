package lk.serenetravels.masterdataservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class MasterDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterDataServiceApplication.class, args);
    }

}
