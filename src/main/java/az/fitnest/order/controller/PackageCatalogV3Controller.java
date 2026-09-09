package az.fitnest.order.controller;

import az.fitnest.order.dto.RandomSubscriptionPackageResponse;
import az.fitnest.order.service.impl.PackageCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/subscription-packages")
@RequiredArgsConstructor
@Tag(name = "Subscription Packages", description = "Mövcud abunəlik paketlərinə baxmaq üçün ucluqlar")
public class PackageCatalogV3Controller {

    private final PackageCatalogService packageCatalogService;

    @Operation(summary = "Təsadüfi paketi əldə edin",
            description = "Bronze, Silver, Gold və ya Platinum paketlərindən birini təsadüfi seçir. Paket adı, aylıq qiymət və zal sayı həmin paketə aiddir; xidmətlər bütün paketlərin unikal faydalarının birləşməsidir.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Təsadüfi paket əldə edildi",
                    content = @Content(schema = @Schema(implementation = RandomSubscriptionPackageResponse.class))),
            @ApiResponse(responseCode = "404", description = "Aktiv paket tapılmadı")
    })
    @GetMapping("/random")
    public ResponseEntity<RandomSubscriptionPackageResponse> getRandomPackage() {
        return ResponseEntity.ok(packageCatalogService.getRandomFeaturedPackage());
    }
}
