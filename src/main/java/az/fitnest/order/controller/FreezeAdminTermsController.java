package az.fitnest.order.controller;

import az.fitnest.order.dto.ApiResponse;
import az.fitnest.order.dto.freeze.FreezeTermsAdminRequest;
import az.fitnest.order.dto.freeze.FreezeTermsAdminResponse;
import az.fitnest.order.service.freeze.FreezeTermsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/freezes/terms")
@RequiredArgsConstructor
@Tag(name = "Freeze Terms Admin", description = "Dondurma (freeze) qayda və şərtlərinin (HTML) administrativ idarə olunması")
public class FreezeAdminTermsController {

    private final FreezeTermsService freezeTermsService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Dondurma qaydalarını əldə et (bütün dillər)", description = "Admin üçün bütün dillərdə (AZ, EN, RU) dondurma (freeze) qaydaları HTML kontentini qaytarır")
    public ResponseEntity<ApiResponse<FreezeTermsAdminResponse>> getTerms() {
        return ResponseEntity.ok(ApiResponse.success(freezeTermsService.getAdminTerms()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Dondurma qaydalarını saxla/yenilə", description = "Admin tərəfindən dondurma qaydalarını (AZ, EN, RU) daxil etmək və ya yeniləmək üçün")
    public ResponseEntity<ApiResponse<FreezeTermsAdminResponse>> saveTerms(@Valid @RequestBody FreezeTermsAdminRequest request) {
        return ResponseEntity.ok(ApiResponse.success(freezeTermsService.saveAdminTerms(request)));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Dondurma qaydalarını sil", description = "Bazada olan dondurma (freeze) qaydalarını silir")
    public ResponseEntity<ApiResponse<Void>> deleteTerms() {
        freezeTermsService.deleteTerms();
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
