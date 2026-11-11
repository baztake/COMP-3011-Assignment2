/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. Adapted from Assignment 1. */
package comp3011.subtitleserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the Assignment 3 subtitle API.
 */
@SpringBootApplication
public class SubtitleServerApplication {

    /**
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(SubtitleServerApplication.class, args);
    }
}
