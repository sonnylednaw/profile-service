package mayson.profileservice.vo;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileVO {
    private String firstName;
    private String lastName;
    private String email;
}
