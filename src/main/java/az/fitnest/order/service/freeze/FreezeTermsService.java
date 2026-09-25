package az.fitnest.order.service.freeze;

import az.fitnest.order.dto.freeze.FreezeTermsAdminRequest;
import az.fitnest.order.dto.freeze.FreezeTermsAdminResponse;
import az.fitnest.order.dto.freeze.FreezeTermsResponse;

public interface FreezeTermsService {

    FreezeTermsAdminResponse getAdminTerms();

    FreezeTermsAdminResponse saveAdminTerms(FreezeTermsAdminRequest request);

    void deleteTerms();

    FreezeTermsResponse getLocalizedTerms(String language);
}
