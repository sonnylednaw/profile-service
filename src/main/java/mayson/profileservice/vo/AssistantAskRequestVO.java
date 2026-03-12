package mayson.profileservice.vo;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantAskRequestVO {
    private String question;
    private String context;
    private String contextJson;
    private String mode;
    private String currency;
    private String timezone;
}
