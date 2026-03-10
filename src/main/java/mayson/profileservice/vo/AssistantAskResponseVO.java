package mayson.profileservice.vo;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantAskResponseVO {
    private String answer;
    private boolean modelUsed;
    private String provider;
}
