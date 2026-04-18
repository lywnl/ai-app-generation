package com.lyw.appgeneration.ai.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageResource implements Serializable {
    private ImageCategoryEnum category;
    private String description;
    private String url;

    @Serial
    private static final long serialVersionUID = 1L;
}
