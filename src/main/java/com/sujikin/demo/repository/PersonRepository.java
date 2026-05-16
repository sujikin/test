package com.sujikin.demo.repository;

import com.sujikin.demo.model.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonRepository extends JpaRepository<Person, Long> {
    boolean existsByNameAndEmail(String name, String email);

    boolean existsByNameAndEmailAndIdNot(String name, String email, Long id);
}
