package az.fitnest.order.controller;

import az.fitnest.order.dto.AdminSubscriptionPackageResponse;
import az.fitnest.order.dto.ApiResponse;
import az.fitnest.order.service.impl.SubscriptionPackageAdminService;
import az.fitnest.order.dto.SubscriptionPackageWithOptionsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/subscription-packages")
@RequiredArgsConstructor
@Tag(name = "Subscription Package Admin", description = "Abunəlik paketlərini idarə etmək üçün administrativ ucluqlar")
public class SubscriptionPackageAdminController {
    @Operation(summary = "Bütün paketləri səhifələmə ilə əldə edin", description = "Bütün abunəlik paketlərini variantları ilə birlikdə səhifələmə ilə qaytarır.")
    @GetMapping("/paged")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<az.fitnest.order.dto.PaginatedResponse<AdminSubscriptionPackageResponse>>> getAllPackagesPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        az.fitnest.order.dto.PaginatedResponse<AdminSubscriptionPackageResponse> result = subscriptionPackageAdminService.getAllPackagesPagedResponse(page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private final SubscriptionPackageAdminService subscriptionPackageAdminService;

    @Operation(summary = "Bütün paketləri əldə edin", description = "Bütün abunəlik paketlərini variantları ilə birlikdə qaytarır.")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<AdminSubscriptionPackageResponse>>> getAllPackages() {
        return ResponseEntity.ok(ApiResponse.success(subscriptionPackageAdminService.getAllPackages()));
    }

    @Operation(
        summary = "Get package names",
        description = "Returns a lightweight list of package IDs and names for dropdowns/filters.",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "List of package names",
                content = @io.swagger.v3.oas.annotations.media.Content(
                    mediaType = "application/json",
                    array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = az.fitnest.order.dto.PackageNameDto.class)
                    )
                )
            )
        }
    )
    @GetMapping("/names")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<az.fitnest.order.dto.PackageNameDto>>> getPackageNames() {
        return ResponseEntity.ok(ApiResponse.success(subscriptionPackageAdminService.getPackageNames()));
    }

    @Operation(summary = "Bütün paket və variant ID-lərini əldə edin", description = "Bütün abunəlik paket ID-lərini və onlara uyğun variant ID-lərini qaytarır.")
    @GetMapping("/ids")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<az.fitnest.order.dto.PackageIdAndOptionIdsResponse>>> getPackageAndOptionIds() {
        return ResponseEntity.ok(ApiResponse.success(subscriptionPackageAdminService.getPackageAndOptionIds()));
    }

    @Operation(summary = "Bütün paket variantlarını əldə edin", description = "Sistemdəki bütün paket variantlarını (Bronze, Silver və s.) düz siyahı şəklində qaytarır.")
    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<az.fitnest.order.dto.AdminPackageOptionDetailResponse>>> getAllOptions() {
        return ResponseEntity.ok(ApiResponse.success(subscriptionPackageAdminService.getAllPackageOptions()));
    }

    @Operation(summary = "Paketi ID ilə əldə edin", description = "Müəyyən abunəlik paketini variantları ilə birlikdə qaytarır.")
    @GetMapping("/{packageId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminSubscriptionPackageResponse>> getPackageById(@PathVariable Long packageId) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionPackageAdminService.getPackageById(packageId)));
    }

    @Operation(summary = "Paket yaradın", description = "Yeni abunəlik paketi yaradır. ADMIN rolu tələb olunur.")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> createPackage(@RequestBody SubscriptionPackageWithOptionsRequest request) {
        subscriptionPackageAdminService.addPackageWithOptions(request);
        return ResponseEntity.status(201).build();
    }

    @Operation(summary = "Paketi yeniləyin", description = "Mövcud abunəlik paketini yeniləyir. ADMIN rolu tələb olunur.")
    @PutMapping("/{packageId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updatePackage(@PathVariable Long packageId, @RequestBody SubscriptionPackageWithOptionsRequest request) {
        subscriptionPackageAdminService.updatePackageWithOptions(packageId, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Paket variantı yaradın", description = "Mövcud abunəlik paketinə yeni variant əlavə edir. ADMIN rolu tələb olunur.")
    @PostMapping("/{packageId}/options")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Long> createOption(@PathVariable Long packageId, @RequestBody az.fitnest.order.dto.PackageOptionEntityDto request) {
        Long optionId = subscriptionPackageAdminService.addOptionToPackage(packageId, request);
        return ResponseEntity.status(201).body(optionId);
    }

    @Operation(summary = "Sadə paket yaradın", description = "Yalnız əsas sahələrlə yeni abunəlik paketi yaradır. ADMIN rolu tələb olunur.")
    @PostMapping("/basic")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Long> createPackage(@RequestBody az.fitnest.order.dto.SubscriptionPackageBasicRequest request) {
        Long packageId = subscriptionPackageAdminService.createPackage(request.name(), request.currency(), request.billingPeriod(), request.isActive());
        return ResponseEntity.status(201).body(packageId);
    }

    @Operation(summary = "Paketi sil", description = "Müəyyən abunəlik paketini və bütün variantlarını silir.")
    @DeleteMapping("/{packageId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePackage(@PathVariable Long packageId) {
        subscriptionPackageAdminService.deletePackageById(packageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bütün paketləri sil", description = "Bütün abunəlik paketlərini və variantlarını silir.")
    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteAllPackages() {
        subscriptionPackageAdminService.deleteAllPackages();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Variantı sil", description = "Müəyyən paket variantını silir.")
    @DeleteMapping("/{packageId}/options/{optionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteOption(@PathVariable Long packageId, @PathVariable Long optionId) {
        subscriptionPackageAdminService.deleteOptionById(packageId, optionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bütün variantları sil", description = "Müəyyən paketə aid bütün variantları silir.")
    @DeleteMapping("/{packageId}/options")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteAllOptions(@PathVariable Long packageId) {
        subscriptionPackageAdminService.deleteAllOptionsByPackageId(packageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Variant statusunu yeniləyin", description = "Müəyyən paket variantının aktiv/passiv statusunu yeniləyir.")
    @PatchMapping("/{packageId}/options/{optionId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateOptionStatus(@PathVariable Long packageId, @PathVariable Long optionId, @RequestParam boolean isActive) {
        subscriptionPackageAdminService.updateOptionStatus(packageId, optionId, isActive);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Paket statusunu yeniləyin", description = "Müəyyən abunəlik paketinin aktiv/passiv statusunu yeniləyir.")
    @PatchMapping("/{packageId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updatePackageStatus(@PathVariable Long packageId, @RequestParam boolean isActive) {
        subscriptionPackageAdminService.updatePackageStatus(packageId, isActive);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Variantı yeniləyin", description = "Müəyyən paket variantını yeniləyir.")
    @PutMapping("/{packageId}/options/{optionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateOption(@PathVariable Long packageId, @PathVariable Long optionId, @RequestBody az.fitnest.order.dto.PackageOptionEntityDto request) {
        subscriptionPackageAdminService.updateOption(packageId, optionId, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Xidmət əlavə edin", description = "Paketə yeni xidmət (üstünlük) əlavə edir.")
    @PostMapping("/{packageId}/benefits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addBenefit(@PathVariable Long packageId, @RequestParam String description) {
        subscriptionPackageAdminService.addBenefit(packageId, description);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Xidməti silin", description = "Paketdən müəyyən bir xidməti (üstünlüyü) silir.")
    @DeleteMapping("/{packageId}/benefits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBenefit(@PathVariable Long packageId, @RequestParam String description) {
        subscriptionPackageAdminService.deleteBenefit(packageId, description);
        return ResponseEntity.noContent().build();
    }
}
