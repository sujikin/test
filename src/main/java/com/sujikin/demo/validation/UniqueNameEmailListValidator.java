package com.sujikin.demo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;
import com.sujikin.demo.model.Person;
import com.sujikin.demo.repository.PersonRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class UniqueNameEmailListValidator implements ConstraintValidator<UniqueNameEmailList, List<Person>> {

    private final PersonRepository personRepository;

    public UniqueNameEmailListValidator(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    @Override
    public void initialize(UniqueNameEmailList constraintAnnotation) {
    }

    @Override
    public boolean isValid(List<Person> persons, ConstraintValidatorContext context) {
        if (persons == null || persons.isEmpty()) {
            return true; // Let @NotEmpty or other annotations handle empty list validation
        }

        boolean isValid = true;
        Set<String> combinations = new HashSet<>();

        // Step 1: Check for duplicates within the list
        for (int i = 0; i < persons.size(); i++) {
            Person person = persons.get(i);
            if (person == null) {
                continue; // Skip null entries, let @Valid handle individual object validation
            }
            String name = person.getName();
            String email = person.getEmail();
            if (name != null && email != null) { // Skip if either is null
                String combination = name + "|" + email;
                if (!combinations.add(combination)) {
                    context.disableDefaultConstraintViolation();
                    context.buildConstraintViolationWithTemplate(
                                    "Duplicate name and email combination found at index " + i + ": " +
                                            name + ", " + email)
                            .addConstraintViolation();
                    isValid = false;
                }
            }
        }

        // Step 2: Check for duplicates in the database
        for (int i = 0; i < persons.size(); i++) {
            Person person = persons.get(i);
            if (person == null) {
                continue; // Skip null entries
            }
            String name = person.getName();
            String email = person.getEmail();
            if (name != null && email != null) { // Skip if either is null
                try {
                    // If the person has an ID, exclude it from the duplicate check (for updates)
                    if (person.getId() != null) {
                        if (personRepository.existsByNameAndEmailAndIdNot(name, email, person.getId())) {
                            context.disableDefaultConstraintViolation();
                            context.buildConstraintViolationWithTemplate(
                                            "The combination of name and email already exists in the database at index " + i + ": " +
                                                    name + ", " + email)
                                    .addConstraintViolation();
                            isValid = false;
                        }
                    } else {
                        // For new persons, just check if the combination exists
                        if (personRepository.existsByNameAndEmail(name, email)) {
                            context.disableDefaultConstraintViolation();
                            context.buildConstraintViolationWithTemplate(
                                            "The combination of name and email already exists in the database at index " + i + ": " +
                                                    name + ", " + email)
                                    .addConstraintViolation();
                            isValid = false;
                        }
                    }
                } catch (Exception e) {
                    // Log the exception for debugging
                    e.printStackTrace();
                    isValid = false;
                }
            }
        }

        return isValid;
    }
}
