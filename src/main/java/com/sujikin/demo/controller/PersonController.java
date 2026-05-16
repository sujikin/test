package com.sujikin.demo.controller;


import com.sujikin.demo.dto.PersonListWrapper;
import com.sujikin.demo.model.Person;
import com.sujikin.demo.repository.PersonRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/persons")
public class PersonController {

    @Autowired
    private PersonRepository personRepository;

    @PostMapping("/bulk")
    public ResponseEntity<?> createPersons(@Valid @RequestBody PersonListWrapper wrapper) {
        try {
            List<Person> savedPersons = personRepository.saveAll(wrapper.getPersons());
            return ResponseEntity.ok(savedPersons);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Keep the single person endpoint as well
    @PostMapping
    public ResponseEntity<?> createPerson(@Valid @RequestBody Person person) {
        try {
            Person savedPerson = personRepository.save(person);
            return ResponseEntity.ok(savedPerson);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
