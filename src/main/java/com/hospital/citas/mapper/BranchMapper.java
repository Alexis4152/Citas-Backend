package com.hospital.citas.mapper;

import com.hospital.citas.dto.request.BranchRequest;
import com.hospital.citas.dto.response.BranchResponse;
import com.hospital.citas.entity.Branch;
import org.springframework.stereotype.Component;

@Component
public class BranchMapper {

    public void applyRequest(Branch branch, BranchRequest req) {
        branch.setName(req.getName());
        branch.setAddress(req.getAddress());
        branch.setCity(req.getCity());
        branch.setPhone(req.getPhone());
        branch.setOpenTime(req.getOpenTime());
        branch.setCloseTime(req.getCloseTime());
    }

    public BranchResponse toResponse(Branch branch) {
        return BranchResponse.builder()
                .id(branch.getId())
                .name(branch.getName())
                .address(branch.getAddress())
                .city(branch.getCity())
                .phone(branch.getPhone())
                .openTime(branch.getOpenTime())
                .closeTime(branch.getCloseTime())
                .isActive(branch.getIsActive())
                .build();
    }
}
