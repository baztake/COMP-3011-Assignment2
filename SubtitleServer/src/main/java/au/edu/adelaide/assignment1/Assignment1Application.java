package au.edu.adelaide.assignment1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main class for the Assignment 1 application.
 */
@SpringBootApplication
public class Assignment1Application {

    private static final Logger log = LoggerFactory.getLogger(Assignment1Application.class);

    /**
     * The main method that starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
	static void main(String[] args) {
        log.info("Starting Assignment1Application");
		SpringApplication.run(Assignment1Application.class, args);
	}

}
