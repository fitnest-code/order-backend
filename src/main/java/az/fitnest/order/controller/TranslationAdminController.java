package az.fitnest.order.controller;
 
import az.fitnest.order.dto.ApiResponse;
import az.fitnest.order.dto.request.CreateTranslationRequest;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.model.entity.Translation;
import az.fitnest.order.repository.TranslationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
 
import java.util.List;
 
 @RestController
 @RequestMapping("/api/v1/admin/subscription-packages/translations")
 @RequiredArgsConstructor
 @Tag(name = "Translation Management", description = "Tərcümələri idarə etmək üçün ucluqlar")
 public class TranslationAdminController {
 
     private final TranslationRepository translationRepository;
 
     @Operation(summary = "Tərcümə yaradın", description = "Verilmiş obyekt, dil və sahə üçün yeni tərcümə yaradır.")
     @ApiResponses(value = {
             @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tərcümə uğurla yaradıldı",
                     content = @Content(schema = @Schema(implementation = Translation.class))),
             @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Tərcümə artıq mövcuddur")
     })
     @PostMapping
     @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<ApiResponse<Translation>> createTranslation(@RequestBody CreateTranslationRequest request) {
         String normalizedEntityType = request.entityType().toUpperCase();
         Translation existing = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                 normalizedEntityType, request.entityId(), request.languageCode().toUpperCase(), request.fieldName()
         ).orElse(null);
 
         if (existing != null) {
             throw new ConflictException("Translation for this field already exists");
         }
 
         Translation translation = Translation.builder()
                 .entityType(normalizedEntityType)
                 .entityId(request.entityId())
                 .languageCode(request.languageCode().toUpperCase())
                 .fieldName(request.fieldName())
                 .fieldValue(request.fieldValue())
                 .build();
         Translation saved = translationRepository.save(translation);
         return ResponseEntity.ok(ApiResponse.success(saved));
     }
 
     @DeleteMapping("/{id}")
     @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<Void> deleteTranslation(@PathVariable Long id) {
         if (!translationRepository.existsById(id)) {
             return ResponseEntity.notFound().build();
         }
         translationRepository.deleteById(id);
         return ResponseEntity.noContent().build();
     }
 
     @GetMapping
     @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<List<Translation>> getTranslations(
             @RequestParam(required = false) String entityType,
             @RequestParam(required = false) String entityId,
             @RequestParam(required = false) String fieldName,
             @RequestParam(required = false) String languageCode) {
         List<Translation> results = translationRepository.findAll().stream()
                 .filter(t -> entityType == null || t.getEntityType().equalsIgnoreCase(entityType))
                 .filter(t -> entityId == null || t.getEntityId().equals(entityId))
                 .filter(t -> fieldName == null || t.getFieldName().equalsIgnoreCase(fieldName))
                 .filter(t -> languageCode == null || t.getLanguageCode().equalsIgnoreCase(languageCode))
                 .toList();
         return ResponseEntity.ok(results);
     }

     @PutMapping
     @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<ApiResponse<Translation>> saveOrUpdateTranslation(@RequestBody CreateTranslationRequest request) {
         String normalizedEntityType = request.entityType().toUpperCase();
         Translation existing = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                 normalizedEntityType, request.entityId(), request.languageCode().toUpperCase(), request.fieldName()
         ).orElse(null);

         Translation saved;
         if (existing != null) {
             existing.setFieldValue(request.fieldValue());
             saved = translationRepository.saveAndFlush(existing);
         } else {
             Translation translation = Translation.builder()
                     .entityType(normalizedEntityType)
                     .entityId(request.entityId())
                     .languageCode(request.languageCode().toUpperCase())
                     .fieldName(request.fieldName())
                     .fieldValue(request.fieldValue())
                     .build();
             saved = translationRepository.saveAndFlush(translation);
         }
         return ResponseEntity.ok(ApiResponse.success(saved));
     }

     @PutMapping("/bulk")
     @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<ApiResponse<List<Translation>>> saveOrUpdateTranslationsBulk(@RequestBody List<CreateTranslationRequest> requests) {
         List<Translation> savedList = new java.util.ArrayList<>();
         for (CreateTranslationRequest request : requests) {
             String normalizedEntityType = request.entityType().toUpperCase();
             Translation existing = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                     normalizedEntityType, request.entityId(), request.languageCode().toUpperCase(), request.fieldName()
             ).orElse(null);

             Translation saved;
             if (existing != null) {
                 existing.setFieldValue(request.fieldValue());
                 saved = translationRepository.saveAndFlush(existing);
             } else {
                 Translation translation = Translation.builder()
                         .entityType(normalizedEntityType)
                         .entityId(request.entityId())
                         .languageCode(request.languageCode().toUpperCase())
                         .fieldName(request.fieldName())
                         .fieldValue(request.fieldValue())
                         .build();
                 saved = translationRepository.saveAndFlush(translation);
             }
             savedList.add(saved);
         }
         return ResponseEntity.ok(ApiResponse.success(savedList));
     }
 }
