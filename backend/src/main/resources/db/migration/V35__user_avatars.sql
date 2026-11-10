-- Instead of a photo, people can pick one of the app's built-in avatars for their account (My
-- account). One picture at a time: choosing an avatar removes the photo and uploading a photo clears
-- the avatar. The codes match ProfilePhotoService.AVATARS and the web app's avatars.ts.
ALTER TABLE users
    ADD COLUMN avatar VARCHAR(20) NULL AFTER photo_version,
    ADD CONSTRAINT ck_user_avatar CHECK (avatar IN ('fox', 'cat', 'dog', 'panda', 'koala', 'lion', 'tiger', 'frog',
                                                    'owl', 'penguin', 'rabbit', 'bear'));
