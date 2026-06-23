package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Builder
public record PackageIdAndOptionIdsResponse(
    @JsonProperty("package_id")
    Long packageId,

    @JsonProperty("option_ids")
    List<Long> optionIds
) {}
