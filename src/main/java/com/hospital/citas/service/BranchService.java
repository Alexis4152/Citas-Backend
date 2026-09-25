package com.hospital.citas.service;

import com.hospital.citas.dto.request.BranchRequest;
import com.hospital.citas.dto.response.BranchResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BranchService {
    List<BranchResponse> listActive();
    Page<BranchResponse> adminSearch(String query, Pageable pageable);
    BranchResponse create(BranchRequest request);
    BranchResponse update(Long id, BranchRequest request);
    void deactivate(Long id);
}
