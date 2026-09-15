package com.spring_cgv_24th.domain.store.service;

import com.spring_cgv_24th.domain.store.dto.ProductResDTO;
import com.spring_cgv_24th.domain.store.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final ProductRepository productRepository;

    public List<ProductResDTO> getProducts() {
        return productRepository.findAll(Sort.by("id")).stream()
                .map(ProductResDTO::from)
                .toList();
    }
}
